package com.sim.darkmask

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 主界面：权限引导 + 完整设置 + HyperOS 3 保活指引。
 * 设置变更会实时通过广播同步给运行中的 OverlayService。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusOverlay: TextView
    private lateinit var statusBattery: TextView
    private lateinit var statusNotif: TextView
    private lateinit var btnToggle: Button
    private lateinit var seekOpacity: SeekBar
    private lateinit var tvOpacity: TextView
    private lateinit var seekH: SeekBar
    private lateinit var seekS: SeekBar
    private lateinit var seekL: SeekBar
    private lateinit var swAutoNight: Switch
    private lateinit var swHideFab: Switch
    private lateinit var llPresets: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusOverlay = findViewById(R.id.tv_status_overlay)
        statusBattery = findViewById(R.id.tv_status_battery)
        statusNotif = findViewById(R.id.tv_status_notif)
        btnToggle = findViewById(R.id.btn_toggle)
        seekOpacity = findViewById(R.id.seek_opacity)
        tvOpacity = findViewById(R.id.tv_opacity_val)
        seekH = findViewById(R.id.seek_h)
        seekS = findViewById(R.id.seek_s)
        seekL = findViewById(R.id.seek_l)
        swAutoNight = findViewById(R.id.sw_autonight)
        swHideFab = findViewById(R.id.sw_hidefab)
        llPresets = findViewById(R.id.ll_presets)

        findViewById<Button>(R.id.btn_perm_overlay).setOnClickListener { requestOverlay() }
        findViewById<Button>(R.id.btn_perm_battery).setOnClickListener { requestBattery() }
        findViewById<Button>(R.id.btn_perm_xiaomi).setOnClickListener { openXiaomi() }
        btnToggle.setOnClickListener { toggleService() }

        seekOpacity.max = 90
        seekOpacity.setOnSeekBarChangeListener(simple { p ->
            val o = p + 5
            Prefs.setOpacity(this, o); tvOpacity.text = "$o%"; applyToService()
        })
        seekH.max = 360; seekS.max = 100; seekL.max = 100
        val hsl = simple { _ ->
            Prefs.setColor(this, ColorUtil.hslToRgb(seekH.progress, seekS.progress, seekL.progress))
            applyToService()
        }
        seekH.setOnSeekBarChangeListener(hsl)
        seekS.setOnSeekBarChangeListener(hsl)
        seekL.setOnSeekBarChangeListener(hsl)
        swAutoNight.setOnCheckedChangeListener { _, c -> Prefs.setAutoNight(this, c); applyToService() }
        swHideFab.setOnCheckedChangeListener { _, c -> Prefs.setHideFab(this, c); applyToService() }

        buildPresets()
        findViewById<Button>(R.id.btn_save_slot).setOnClickListener {
            val cur = Prefs.getColor(this)
            val idx = (0..2).firstOrNull { Prefs.getSlot(this, it) < 0 } ?: 0
            Prefs.setSlot(this, idx, cur)
            buildPresets()
            Toast.makeText(this, "已保存到槽${idx + 1}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        syncUI()
    }

    private fun refreshStatus() {
        statusOverlay.text = "悬浮窗：" +
            if (Settings.canDrawOverlays(this)) "✅ 已授予" else "❌ 未授予（需手动开启）"
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        statusBattery.text = "电池优化：" +
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                pm.isIgnoringBatteryOptimizations(packageName)
            ) "✅ 已豁免" else "❌ 未豁免（建议关闭）"
        statusNotif.text = "通知：" +
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            ) "✅ 已允许" else "❌ 未允许"
        btnToggle.text = if (OverlayService.isRunning(this)) "停止蒙版" else "启动蒙版"
    }

    private fun syncUI() {
        val o = Prefs.getOpacity(this)
        seekOpacity.progress = o - 5
        tvOpacity.text = "$o%"
        swAutoNight.isChecked = Prefs.isAutoNight(this)
        swHideFab.isChecked = Prefs.isHideFab(this)
        val (h, s, l) = ColorUtil.rgbToHsl(Prefs.getColor(this))
        seekH.progress = h; seekS.progress = s; seekL.progress = l
    }

    /** 预设区：仅保留一个「黑」，再加 3 个用户槽位（点=应用，长按=存当前色）。 */
    private fun buildPresets() {
        llPresets.removeAllViews()
        val black = Button(this).apply {
            text = "黑"
            setOnClickListener { applyColor(Color.BLACK) }
        }
        llPresets.addView(black)
        for (i in 0..2) {
            val col = Prefs.getSlot(this, i)
            val btn = Button(this).apply {
                text = if (col >= 0) "槽${i + 1}" else "＋"
                if (col >= 0) setBackgroundColor(col)
                setOnClickListener { if (col >= 0) applyColor(col) }
                setOnLongClickListener {
                    Prefs.setSlot(this@MainActivity, i, Prefs.getColor(this@MainActivity))
                    buildPresets()
                    Toast.makeText(this@MainActivity, "已保存到槽${i + 1}", Toast.LENGTH_SHORT).show()
                    true
                }
            }
            llPresets.addView(btn)
        }
    }

    private fun applyColor(c: Int) {
        Prefs.setColor(this, c)
        val (h, s, l) = ColorUtil.rgbToHsl(c)
        seekH.progress = h; seekS.progress = s; seekL.progress = l
        applyToService()
    }

    private fun requestOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } else Toast.makeText(this, "悬浮窗权限已授予", Toast.LENGTH_SHORT).show()
    }

    private fun requestBattery() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (_: Exception) { }
            } else Toast.makeText(this, "已豁免电池优化", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openXiaomi() {
        val candidates = listOf(
            Intent().setClassName(
                "com.miui.securitycenter", "com.miui.permcenter.autoset.AutoSetActivity"),
            Intent().setClassName(
                "com.miui.securitycenter", "com.miui.permcenter.permissions.AppPermissionsEditorActivity"),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        )
        for (it in candidates) {
            try { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Exception) { }
        }
    }

    private fun toggleService() {
        if (OverlayService.isRunning(this)) {
            sendBroadcast(Intent(OverlayService.ACTION_STOP).setPackage(packageName))
            Handler(Looper.getMainLooper()).postDelayed({ refreshStatus() }, 500)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            requestOverlay()
            Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_LONG).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        val i = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
        Prefs.setEnabled(this, true)
        Handler(Looper.getMainLooper()).postDelayed({ refreshStatus() }, 600)
    }

    private fun applyToService() {
        if (OverlayService.isRunning(this))
            sendBroadcast(Intent(OverlayService.ACTION_APPLY).setPackage(packageName))
    }

    private fun simple(onProgress: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) onProgress(p) }
        override fun onStartTrackingTouch(s: SeekBar?) {}
        override fun onStopTrackingTouch(s: SeekBar?) {}
    }
}
