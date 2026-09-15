package com.example.nodecontainer

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Device Owner 接收器。容器在受管配置/设备所有者模式下借此获得静默装卸应用、
 * 锁屏、设密码、Kiosk、用户限制等特权 —— 这些能力经 HostBridge 的方法门禁对外暴露
 * （见 HostBridgeService.kt 的 device_owner 方法组）。
 *
 * 激活方式（需 adb / 预置配置）：
 *   adb shell dpm set-device-owner com.example.nodecontainer/.DeviceAdminReceiver
 */
class DeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        Log.i(TAG, "Device Owner 已启用")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.i(TAG, "Device Owner 已停用")
    }

    companion object {
        const val TAG = "DeviceAdminReceiver"
    }
}
