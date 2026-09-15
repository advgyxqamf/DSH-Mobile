package com.example.nodecontainer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * DSH 无障碍服务 —— ui_automation 方法组的**真实执行体**。
 *
 * 职责（对应 BRIDGE_PROTOCOL.md §3.2 的六个方法）：
 *   ui.tap        → [performTap]   GestureDescription 单点手势（API 24+）
 *   ui.swipe      → [performSwipe] GestureDescription 路径手势
 *   ui.inputText  → [inputText]    ACTION_SET_TEXT（API 21+）→ ACTION_PASTE 兜底
 *   ui.getUiTree  → [dumpUiTree]   rootInActiveWindow + getWindows 递归采集
 *   ui.waitFor    → [waitForNode]  轮询轮询节点出现（由 HostBridgeService 驱动）
 *   ui.screenshot → 不在此（需 MediaProjection，属 P5）
 *
 * 与 HostBridgeService 的连接方式：HostBridgeService 跑在**主进程**，AccessibilityService
 * 由系统在同一进程绑定（Manifest 未指定 android:process），因此二者共享进程内的
 * [instance] 静态引用。HostBridgeService 调用前一律经 [isReady] 判定，服务未连接时
 * 抛 ERR_CAPABILITY_MISSING(-32001) —— 与 spec 的降级语义一致。
 *
 * 配置前提（app/src/main/res/xml/accessibility_service_config.xml）：
 *   canRetrieveWindowContent="true"          → getRootInActiveWindow / getWindows 可用
 *   flagReportViewIds                        → viewIdResourceName 非空
 *   flagRetrieveInteractiveWindows           → getWindows 可拿到悬浮窗 / 输入法窗口
 *
 * ⚠ 线程：本类的公开方法由 HostBridgeService 的工作线程调用。dispatchGesture /
 *   findAccessibilityNodeInfosByViewId / performAction 均线程安全（内部走 binder 到
 *   系统无障碍服务），但 [waitForNode] 的 sleep 轮询必须由调用线程阻塞，故放在
 *   HostBridgeService 的缓存线程池中执行（executor 已是无界线程池，不阻塞 accept 循环）。
 */
class DshAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 被动读取型服务：节点树按需采集，不在此消费事件流（避免高频回调拖累系统）。
    }

    override fun onInterrupt() {
        Log.i(TAG, "onInterrupt：系统中断了服务反馈")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "DshAccessibilityService 已连接（ui_automation 能力可用）")
        RuntimeDiagnostics.append(this, "accessibility", true, "无障碍服务已连接", "ui_automation 能力可用")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        Log.i(TAG, "DshAccessibilityService 已解绑")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ---- ui.tap ----

    /**
     * 单击坐标 (x, y)。
     * @return true=手势已派发；false=手势被系统取消（通常因屏幕被其他手势占用）。
     */
    fun performTap(x: Float, y: Float, durationMs: Long = 60L): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return dispatchPath(path, durationMs.coerceIn(1L, 3000L))
    }

    // ---- ui.swipe ----

    /** 从 (x1,y1) 滑动到 (x2,y2)；durationMs 越大越接近「拖拽」。 */
    fun performSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300L): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        return dispatchPath(path, durationMs.coerceIn(1L, 10000L))
    }

    /** 公共手势派发：同步等待系统确认（用轮询等待，避免依赖 API 30+ 的回调 Future）。 */
    private fun dispatchPath(path: Path, durationMs: Long): Boolean {
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        var result = false
        val latch = java.util.concurrent.CountDownLatch(1)
        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(desc: GestureDescription?) { result = true; latch.countDown() }
                override fun onCancelled(desc: GestureDescription?) { result = false; latch.countDown() }
            },
            null
        )
        if (!dispatched) return false
        // 手势最长 durationMs + 800ms 余量；超时按失败处理（避免调用线程挂死）。
        latch.await(durationMs + 800L, java.util.concurrent.TimeUnit.MILLISECONDS)
        return result
    }

    // ---- ui.getUiTree ----

    /**
     * 采集当前界面节点树。
     *
     * 双来源合并：
     *  1) getWindows()      —— 覆盖悬浮窗 / 输入法 / 系统弹窗（getRootInActiveWindow 拿不到）
     *  2) rootInActiveWindow —— 保底：部分 ROM 在无焦点窗口时 getWindows 为空
     *
     * 节点序列化为 JSON：{cls, pkg, id, text, desc, bounds:[l,t,r,b], clickable, editable,
     * scrollable, enabled, focused, children:[...]}
     */
    fun dumpUiTree(maxNodes: Int = 3000, maxDepth: Int = 40): JSONObject {
        val windows = JSONArray()
        var budget = intArrayOf(maxNodes)

        try {
            val list = getWindows()
            if (list != null) {
                for (w in list) {
                    val root = w.root ?: continue
                    windows.put(
                        JSONObject().apply {
                            put("id", w.id)
                            put("type", windowTypeName(w.type))
                            put("layer", w.layer)
                            put("active", w.isActive)
                            put("focused", w.isFocused)
                            put("node", nodeToJson(root, 0, maxDepth, budget) ?: JSONObject.NULL)
                        }
                    )
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "getWindows 采集失败", e)
        }

        // 保底：活动窗口
        if (windows.length() == 0) {
            val root = try { rootInActiveWindow } catch (_: Throwable) { null }
            if (root != null) {
                windows.put(
                    JSONObject().apply {
                        put("id", -1)
                        put("type", "active")
                        put("layer", -1)
                        put("active", true)
                        put("focused", true)
                        put("node", nodeToJson(root, 0, maxDepth, budget) ?: JSONObject.NULL)
                    }
                )
            }
        }

        return JSONObject().apply {
            put("windows", windows)
            put("windowCount", windows.length())
            put("truncated", budget[0] <= 0)
        }
    }

    private fun windowTypeName(type: Int): String = when (type) {
        AccessibilityWindowInfo.TYPE_APPLICATION -> "application"
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "input_method"
        AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "accessibility_overlay"
        AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "split_screen_divider"
        else -> "unknown($type)"
    }

    /**
     * 递归序列化节点。budget 是**跨整棵树共享**的节点预算（数组包装以实现按引用递减），
     * 防止某些 ROM 的超大节点树把 JSON 撑爆。
     */
    private fun nodeToJson(
        node: AccessibilityNodeInfo?,
        depth: Int,
        maxDepth: Int,
        budget: IntArray
    ): JSONObject? {
        if (node == null) return null
        if (depth > maxDepth || budget[0] <= 0) return null
        budget[0] -= 1

        val rect = Rect()
        try { node.getBoundsInScreen(rect) } catch (_: Throwable) {}

        val children = JSONArray()
        try {
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val c = nodeToJson(child, depth + 1, maxDepth, budget)
                if (c != null) children.put(c)
            }
        } catch (_: Throwable) {}

        return JSONObject().apply {
            put("cls", node.className?.toString() ?: "")
            put("pkg", node.packageName?.toString() ?: "")
            // 需 flagReportViewIds；未声明时该属性为 null
            put("id", node.viewIdResourceName ?: "")
            put("text", node.text?.toString() ?: "")
            put("desc", node.contentDescription?.toString() ?: "")
            put("bounds", JSONArray().apply {
                put(rect.left); put(rect.top); put(rect.right); put(rect.bottom)
            })
            put("clickable", node.isClickable)
            // isEditable 为 API 18+，minSdk=24 恒可调
            put("editable", node.isEditable)
            put("scrollable", node.isScrollable)
            put("enabled", node.isEnabled)
            put("focused", node.isFocused)
            put("checked", if (node.isCheckable) node.isChecked else JSONObject.NULL)
            put("children", children)
        }
    }

    // ---- ui.inputText ----

    /**
     * 向当前输入焦点写入文本。
     *
     * 三级降级（越靠前越通用）：
     *  1. 「输入焦点节点」performAction(ACTION_SET_TEXT)  —— 最标准，一次到位
     *  2. 「可编辑节点」逐个尝试 ACTION_FOCUS + ACTION_SET_TEXT
     *  3. 剪贴板 ACTION_PASTE —— 部分自绘控件（Compose / Flutter / 游戏引擎）不吃 SET_TEXT
     *
     * @param selector 可选：{id: "pkg:id/xxx"} 或 {text: "占位符文本"}，用于精确定位输入框
     */
    fun inputText(text: String, selector: JSONObject? = null): Boolean {
        val target = findEditableTarget(selector) ?: return false

        if (setTextOn(target, text)) return true

        // 降级：聚焦 + 粘贴
        return try {
            if (!target.isFocused) target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("dsh", text))
            target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } catch (e: Throwable) {
            Log.w(TAG, "ACTION_PASTE 降级失败", e)
            false
        }
    }

    /** ACTION_SET_TEXT 是 API 21+；需节点 isEditable（否则系统忽略该 action）。 */
    private fun setTextOn(node: AccessibilityNodeInfo, text: String): Boolean = try {
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    } catch (e: Throwable) {
        Log.w(TAG, "ACTION_SET_TEXT 失败", e)
        false
    }

    /** 定位输入目标：显式 selector 优先，其次输入焦点，最后整棵树里第一个可编辑节点。 */
    private fun findEditableTarget(selector: JSONObject?): AccessibilityNodeInfo? {
        if (selector != null && selector.length() > 0) {
            selector.optString("id", "").takeIf { it.isNotBlank() }?.let { id ->
                val byId = findViewIdNode(id)
                if (byId != null && byId.isEditable) return byId
                if (byId != null) return byId
            }
            selector.optString("text", "").takeIf { it.isNotBlank() }?.let { t ->
                findTextNode(t)?.let { return it }
            }
        }
        // 输入焦点
        try {
            findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { if (it.isEditable) return it }
        } catch (_: Throwable) {}
        // 全树扫描
        return findFirstEditable(activeRoots(), 0)
    }

    private fun findViewIdNode(viewId: String): AccessibilityNodeInfo? = try {
        activeRoots().firstNotNullOfOrNull { root ->
            root.findAccessibilityNodeInfosByViewId(viewId)?.firstOrNull()
        }
    } catch (_: Throwable) { null }

    private fun findTextNode(text: String): AccessibilityNodeInfo? = try {
        activeRoots().firstNotNullOfOrNull { root ->
            root.findAccessibilityNodeInfosByText(text)?.firstOrNull()
        }
    } catch (_: Throwable) { null }

    private fun findFirstEditable(
        nodes: List<AccessibilityNodeInfo>,
        depth: Int
    ): AccessibilityNodeInfo? {
        if (depth > 40) return null
        for (n in nodes) {
            if (n.isEditable) return n
            val kids = (0 until n.childCount).mapNotNull { n.getChild(it) }
            findFirstEditable(kids, depth + 1)?.let { return it }
        }
        return null
    }

    /**
     * 当前所有可检索窗口的根节点（含 IME / 悬浮窗）。getWindows 为空时回落 rootInActiveWindow。
     * 用于 ui.waitFor 的轮询判定。
     */
    fun activeRoots(): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        try {
            getWindows()?.forEach { w -> w.root?.let { out.add(it) } }
        } catch (_: Throwable) {}
        if (out.isEmpty()) {
            try { rootInActiveWindow?.let { out.add(it) } } catch (_: Throwable) {}
        }
        return out
    }

    // ---- ui.waitFor ----

    /**
     * 轮询等待条件成立。条件三选一（按优先级）：
     *   {id: "pkg:id/xxx"} / {text: "..."} / {className: "android.widget.Button"}，
     *   可选 {pkg: "目标包名"} 限定包名。
     *
     * @return 命中返回 {found:true, elapsedMs, node:{...}}；超时返回 {found:false, elapsedMs}
     */
    fun waitForNode(selector: JSONObject, timeoutMs: Long, intervalMs: Long = 250L): JSONObject {
        val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(0L, 120_000L)
        val step = intervalMs.coerceIn(50L, 2000L)
        val startedAt = System.currentTimeMillis()

        while (System.currentTimeMillis() <= deadline) {
            val root = try { rootInActiveWindow } catch (_: Throwable) { null }
            if (root != null) {
                val hit = matchNode(root, selector)
                if (hit != null) {
                    return JSONObject().apply {
                        put("found", true)
                        put("elapsedMs", System.currentTimeMillis() - startedAt)
                        put("node", nodeToJson(hit, 0, 40, intArrayOf(2000)) ?: JSONObject.NULL)
                    }
                }
            }
            try { Thread.sleep(step) } catch (_: InterruptedException) { break }
        }
        return JSONObject().apply {
            put("found", false)
            put("elapsedMs", System.currentTimeMillis() - startedAt)
        }
    }

    /** 深度优先匹配 selector；返回首个命中节点。 */
    private fun matchNode(
        node: AccessibilityNodeInfo?,
        selector: JSONObject,
        depth: Int = 0
    ): AccessibilityNodeInfo? {
        if (node == null || depth > 40) return null

        val wantId = selector.optString("id", "")
        val wantText = selector.optString("text", "")
        val wantCls = selector.optString("className", "")
        val wantPkg = selector.optString("pkg", "")

        val okPkg = wantPkg.isEmpty() || node.packageName?.toString() == wantPkg
        if (okPkg) {
            val okId = wantId.isEmpty() || node.viewIdResourceName == wantId
            val okText = wantText.isEmpty() ||
                node.text?.toString()?.contains(wantText) == true ||
                node.contentDescription?.toString()?.contains(wantText) == true
            val okCls = wantCls.isEmpty() || node.className?.toString() == wantCls
            if (okId && okText && okCls && (wantId.isNotEmpty() || wantText.isNotEmpty() || wantCls.isNotEmpty())) {
                return node
            }
        }

        for (i in 0 until node.childCount) {
            matchNode(node.getChild(i), selector, depth + 1)?.let { return it }
        }
        return null
    }

    companion object {
        const val TAG = "DshAccessibilityService"

        /**
         * 进程内单例引用。HostBridgeService 与 DshAccessibilityService 同进程
         * （Manifest 未给 accessibility service 指定 android:process），故可直接引用。
         * 服务未连接时为 null —— 调用方据此抛 -32001。
         */
        @Volatile
        var instance: DshAccessibilityService? = null
            private set

        /** 服务是否已连接且可执行手势/读屏。 */
        fun isReady(): Boolean = instance != null
    }
}
