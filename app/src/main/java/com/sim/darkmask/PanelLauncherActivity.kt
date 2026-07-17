package com.sim.darkmask

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * 透明中转 Activity：点击通知时启动它，立即向 OverlayService 发出"打开控制面板"广播后 finish。
 * 用 Activity（而非直接 broadcast content intent）是为了让系统自动折叠通知栏抽屉，
 * 保证弹出的悬浮控制面板不会被通知阴影盖住。
 */
class PanelLauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sendBroadcast(Intent(OverlayService.ACTION_PANEL).setPackage(packageName))
        finish()
    }
}
