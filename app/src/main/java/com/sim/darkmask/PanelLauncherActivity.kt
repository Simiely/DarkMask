package com.sim.darkmask

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle

class PanelLauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!OverlayService.isRunning(this)) {
            // 服务未运行则启动它，确保蒙版/面板就绪
            val i = Intent(this, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
            // 给服务一点时间建立视图再弹面板
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                sendBroadcast(Intent(OverlayService.ACTION_PANEL).setPackage(packageName))
                finish()
            }, 300)
        } else {
            sendBroadcast(Intent(OverlayService.ACTION_PANEL).setPackage(packageName))
            finish()
        }
    }
}
