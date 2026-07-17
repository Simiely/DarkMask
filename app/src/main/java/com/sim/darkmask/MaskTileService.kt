package com.sim.darkmask

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * 通知栏快捷开关磁贴：点按即切换蒙版。
 */
class MaskTileService : TileService() {

    companion object {
        fun update(ctx: android.content.Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    TileService.requestListeningState(ctx, ComponentName(ctx, MaskTileService::class.java))
                } catch (_: Exception) { }
            }
        }
    }

    override fun onStartListening() = refresh()

    override fun onClick() {
        if (isSecure()) return  // 锁屏状态不响应
        sendBroadcast(Intent(OverlayService.ACTION_TOGGLE).setPackage(packageName))
        refresh()
    }

    private fun refresh() {
        val enabled = Prefs.isEnabled(this)
        qsTile?.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        qsTile?.updateTile()
    }
}
