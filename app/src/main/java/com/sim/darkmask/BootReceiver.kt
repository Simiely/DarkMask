package com.sim.darkmask

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机 / 应用更新后自启服务。
 * 注意：HyperOS 需用户在「自启动管理」中手动授权，否则接收不到 BOOT_COMPLETED。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            if (Prefs.isEnabled(ctx)) {
                ctx.startForegroundService(Intent(ctx, OverlayService::class.java))
            }
        }
    }
}
