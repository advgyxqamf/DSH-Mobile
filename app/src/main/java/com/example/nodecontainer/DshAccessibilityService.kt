package com.example.nodecontainer

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.util.Log

/**
 * DSH 无障碍服务。容器在开启无障碍后，HostBridge 的 ui_automation 方法组
 * （tap/swipe/inputText/getUiTree/waitFor）方可对外暴露（bridge:ui_automation 能力）。
 *
 * 真实 UI 自动化由内核侧经此服务完成手势/节点树采集；本类提供能力声明与生命周期钩子，
 * 具体手势执行在集成分支基于 AccessibilityService 的 dispatchGesture / node tree API 实现。
 */
class DshAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 读取型服务：按需采集节点树；此处不主动消费事件。
    }

    override fun onInterrupt() {}

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "DshAccessibilityService 已连接（ui_automation 能力可用）")
    }

    companion object {
        const val TAG = "DshAccessibilityService"
    }
}
