package com.example.nodecontainer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开机自启：收到 BOOT_COMPLETED（含厂商 QUICKBOOT_POWERON）后拉起 NodeRuntimeService。
 * 保证容器（L0）在内核（L1）需要之前就位 —— 这是“冻结 APK 是地基”的落地。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            Log.i(TAG, "BootReceiver: $action -> 启动 NodeRuntimeService")
            val svc = Intent(context, NodeRuntimeService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(svc)
            } else {
                context.startService(svc)
            }
        }
    }

    companion object {
        const val TAG = "BootReceiver"
    }
}
