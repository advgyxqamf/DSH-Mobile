package com.example.nodecontainer

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

/**
 * 应用入口：仅做一件事——注册前台服务所需的通知渠道。
 * （Android 8+ 要求前台服务先有渠道，否则 startForeground 抛异常。）
 */
class NodeContainerApp : Application() {
    companion object {
        const val NOTIFICATION_CHANNEL_ID = "node_runtime"
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Node Runtime",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Node.js 运行时前台服务" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }
}
