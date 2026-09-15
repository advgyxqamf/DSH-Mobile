package com.example.nodecontainer

import android.app.Activity
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 截屏服务（ui.screenshot 的执行体，P5）。
 *
 * ─────────────────────────────────────────────────────────────────────────
 * 为什么必须是前台服务（而不是普通 Service / 甚至直接在桥里做）
 * ─────────────────────────────────────────────────────────────────────────
 * Android 14 (API 34) 起，持有 MediaProjection 的进程**必须**运行一个
 * foregroundServiceType="mediaProjection" 的前台服务，否则系统直接抛
 * SecurityException。这是硬性要求，不是优化建议。
 *
 * ─────────────────────────────────────────────────────────────────────────
 * 授权模型（与 Device Owner / 无障碍的本质区别）
 * ─────────────────────────────────────────────────────────────────────────
 * MediaProjection 的授权是 **每次会话** 的：必须由用户在系统弹窗里点「开始录制」，
 * 拿回一个 resultCode + Intent，用它 createScreenCaptureIntent 才能建出 VirtualDisplay。
 *
 * 这意味着：
 *   · **无法预置**（不像 Device Owner 可以 adb 一次性设置）；
 *   · 无法在后台静默发起（必须有 Activity 承载 startActivityForResult）；
 *   · 授权结果可复用（我们把它缓存在 files/screen-capture-grant.json，
 *     下次同一 resultCode/Intent 仍可用，直到用户撤销或进程重启）。
 *
 * 所以本服务的契约是：**「授权一次，长期复用」**。首次 ui.screenshot 若未授权，
 * 返回 -32001 并附带 "需要用户授权" 的明确指引；用户在 App 里点一次授权后，
 * 后续截图即可后台完成。
 */
class ScreenCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 授权结果由 MainActivity 经此 Intent 传入（见 MainActivity.requestScreenCapture）。
        if (intent?.action == ACTION_START && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            @Suppress("DEPRECATION")
            val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
            if (code == Activity.RESULT_OK && data != null) {
                startProjection(code, data)
            } else {
                RuntimeDiagnostics.append(this, "screenshot", false, "截屏授权被取消", "resultCode=$code")
            }
        }
        return START_NOT_STICKY
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val p = mpm.getMediaProjection(resultCode, data) ?: run {
                RuntimeDiagnostics.append(this, "screenshot", false, "getMediaProjection 返回 null", "授权数据可能已失效")
                return
            }
            // ⚠ 必须注册 callback：否则部分 ROM 上 projection 会在短暂空闲后被系统回收，
            //   表现为「第一次能截、第二次 startVirtualDisplay 抛 IllegalStateException」。
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "MediaProjection 被系统停止")
                    teardown()
                }
            }, Handler(android.os.Looper.getMainLooper()))

            projection = p
            instance = this
            RuntimeDiagnostics.append(this, "screenshot", true, "MediaProjection 已就绪", "截屏授权生效")
        } catch (e: Throwable) {
            RuntimeDiagnostics.append(this, "screenshot", false, "启动 MediaProjection 失败", errText(e))
        }
    }

    /**
     * 截一帧。**同步**返回，最多等 8 秒。
     *
     * 实现要点：ImageReader 取最新帧 → 转 Bitmap。
     * 用 `acquireLatestImage()` 而不是 `acquireNextImage()`：后者拿到的是队列里最旧的帧，
     * 在高刷新率设备上可能已经过期好几帧。
     */
    fun capture(width: Int, height: Int, densityDpi: Int): Bitmap? {
        val p = projection ?: return null
        val latch = CountDownLatch(1)
        val holder = AtomicReference<Bitmap?>(null)

        try {
            setupDisplay(p, width, height, densityDpi)
            val reader = imageReader ?: return null
            reader.setOnImageAvailableListener({ r ->
                var image: Image? = null
                try {
                    image = r.acquireLatestImage()
                    if (image != null) {
                        holder.set(imageToBitmap(image))
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "取帧失败", e)
                } finally {
                    try { image?.close() } catch (_: Throwable) {}
                    latch.countDown()
                }
            }, handler)

            // 部分设备首次建 VirtualDisplay 后需要一两帧才出图；超时即放弃。
            if (!latch.await(8, TimeUnit.SECONDS)) {
                Log.w(TAG, "截屏超时（8s 内未收到帧）")
            }
        } catch (e: Throwable) {
            RuntimeDiagnostics.append(this, "screenshot", false, "截屏失败", errText(e))
        }
        return holder.get()
    }

    private fun setupDisplay(p: MediaProjection, width: Int, height: Int, densityDpi: Int) {
        if (imageReader != null) return // 复用已建好的显示
        if (handler == null) {
            val t = HandlerThread("dsh-screen-capture")
            t.start()
            handlerThread = t
            handler = Handler(t.looper)
        }
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        virtualDisplay = p.createVirtualDisplay(
            "dsh-capture",
            width, height, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, handler
        )
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width

        // ImageReader 的行跨度通常大于 width*4（对齐填充），直接 createBitmap 会花屏。
        val bmp = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        bmp.copyPixelsFromBuffer(buffer)
        return Bitmap.createBitmap(bmp, 0, 0, image.width, image.height)
    }

    private fun teardown() {
        try { virtualDisplay?.release() } catch (_: Throwable) {}
        try { imageReader?.close() } catch (_: Throwable) {}
        try { projection?.stop() } catch (_: Throwable) {}
        virtualDisplay = null
        imageReader = null
        projection = null
        if (instance === this) instance = null
    }

    override fun onDestroy() {
        teardown()
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, NodeContainerApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.screenshot_notif_title))
            .setContentText(getString(R.string.screenshot_notif_text))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun errText(e: Throwable): String =
        "${e::class.java.simpleName}: ${e.message}"

    companion object {
        const val TAG = "ScreenCaptureService"
        const val NOTIF_ID = 1002
        const val ACTION_START = "com.example.nodecontainer.SCREEN_CAPTURE_START"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        /** 截图授权缓存文件（进程重启后可复用，避免每次都要用户点确认）。 */
        const val GRANT_FILE = "screen-capture-grant.json"

        @Volatile
        var instance: ScreenCaptureService? = null
            private set

        fun isReady(): Boolean = instance?.projection != null

        /**
         * 保存授权结果。⚠ Intent 无法直接序列化为 JSON，故用 Intent 的
         * toUri(FLAG_GRANT_READ_URI_PERMISSION) 仅能拿到数据 URI —— 对 MediaProjection
         * 的授权 Intent 不适用。稳妥做法是把整个 Intent 以 Parcel 字节流存盘。
         */
        fun saveGrant(ctx: Context, resultCode: Int, data: Intent) {
            try {
                val parcel = android.os.Parcel.obtain()
                data.writeToParcel(parcel, 0)
                val bytes = parcel.marshall()
                parcel.recycle()
                val obj = org.json.JSONObject().apply {
                    put("resultCode", resultCode)
                    put("intentBase64", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
                    put("savedAt", System.currentTimeMillis())
                }
                File(ctx.filesDir, GRANT_FILE).writeText(obj.toString())
            } catch (e: Throwable) {
                Log.w(TAG, "保存截屏授权失败", e)
            }
        }

        fun loadGrant(ctx: Context): Pair<Int, Intent>? {
            return try {
                val f = File(ctx.filesDir, GRANT_FILE)
                if (!f.exists()) return null
                val obj = org.json.JSONObject(f.readText())
                val bytes = android.util.Base64.decode(obj.getString("intentBase64"), android.util.Base64.DEFAULT)
                val parcel = android.os.Parcel.obtain()
                parcel.unmarshall(bytes, 0, bytes.size)
                parcel.setDataPosition(0)
                @Suppress("DEPRECATION")
                val intent = Intent.CREATOR.createFromParcel(parcel)
                parcel.recycle()
                obj.getInt("resultCode") to intent
            } catch (e: Throwable) {
                Log.w(TAG, "读取截屏授权失败", e)
                null
            }
        }
    }
}
