package com.sim.darkmask

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle

/**
 * 透明中转 Activity：点击通知时启动它，立即向 OverlayService 发出"打开控制面板"广播后 finish。
 * 用 Activity（而非直接 broadcast content intent）是为了让系统自动折叠通知栏抽屉，
 * 保证弹出的悬浮控制面板不会被通知阴影盖住。
 *
 * 若服务未运行则先启动，再发送 ACTION_START_AND_PANEL 广播。
 * startForegroundService() 将 Service 创建排入主线程队列，sendBroadcast() 的广播也异步投递，
 * 两者在主线程上顺序执行，广播投递时 receiver 已就绪，故无需延迟。
 */
class PanelLauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = Intent(OverlayService.ACTION_START_AND_PANEL).setPackage(packageName)
        if (!OverlayService.isRunning(this)) {
            val si = Intent(this, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(si) else startService(si)
        }
        sendBroadcast(intent)
        finish()
    }
}
