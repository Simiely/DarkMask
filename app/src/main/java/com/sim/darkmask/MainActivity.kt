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
    private lateinit var seekR: SeekBar
    private lateinit var seekG: SeekBar
    private lateinit var seekB: SeekBar
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
        seekR = findViewById(R.id.seek_r)
        seekG = findViewById(R.id.seek_g)
        seekB = findViewById(R.id.seek_b)
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
        val rgb = simple { _ ->
            Prefs.setColor(this, Color.rgb(seekR.progress, seekG.progress, seekB.progress))
            applyToService()
        }
        seekR.setOnSeekBarChangeListener(rgb)
        seekG.setOnSeekBarChangeListener(rgb)
        seekB.setOnSeekBarChangeListener(rgb)
        swAutoNight.setOnCheckedChangeListener { _, c -> Prefs.setAutoNight(this, c); applyToService() }
        swHideFab.setOnCheckedChangeListener { _, c -> Prefs.setHideFab(this, c); applyToService() }

        val list = arrayOf(
            "纯黑" to Color.BLACK,
            "暖光" to Color.parseColor("#FF9E5E"),
            "护眼" to Color.parseColor("#7CFC00"),
            "夜红" to Color.parseColor("#FF3B30"),
            "深蓝" to Color.parseColor("#0B1E3F")
        )
        list.forEach { (name, col) ->
            val b = Button(this); b.text = name
            b.setOnClickListener {
                Prefs.setColor(this, col)
                seekR.progress = Color.red(col)
                seekG.progress = Color.green(col)
                seekB.progress = Color.blue(col)
                applyToService()
            }
            llPresets.addView(b)
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
        val col = Prefs.getColor(this)
        seekR.progress = Color.red(col)
        seekG.progress = Color.green(col)
        seekB.progress = Color.blue(col)
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
