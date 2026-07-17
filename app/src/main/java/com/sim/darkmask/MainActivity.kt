package com.sim.darkmask

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 主界面：权限引导 + 完整设置。
 * 设置变更会实时通过广播同步给运行中的 OverlayService。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusOverlay: TextView
    private lateinit var statusNotif: TextView
    private lateinit var btnToggle: Button
    private lateinit var seekOpacity: SeekBar
    private lateinit var tvOpacity: TextView
    private lateinit var seekH: SeekBar
    private lateinit var seekS: SeekBar
    private lateinit var seekL: SeekBar
    private lateinit var swHideFab: Switch
    private lateinit var llPresets: LinearLayout
    private var hslDragging = false

    /**
     * 通知权限请求器。Android 13+ 必须在 POST_NOTIFICATIONS 已授予后，
     * 才能 startForeground()，否则 startForeground() 的通知会被系统静默丢弃。
     * 这里用回调在“用户点允许之后”再启动服务，而不是旧写法那样不等授权就启动。
     */
    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startOverlayServiceNow()
        } else {
            // 被拒绝：蒙版仍可用，但常驻通知不显示。
            if (!ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.POST_NOTIFICATIONS)) {
                // 用户勾选了“不再询问”：直接跳系统通知设置页手动开启。
                Toast.makeText(this, "已为你打开通知设置，请允许“夜深模式”发送通知", Toast.LENGTH_LONG).show()
                openNotificationSettings()
            } else {
                Toast.makeText(this, "通知权限被拒绝，常驻通知将不显示", Toast.LENGTH_LONG).show()
            }
            startOverlayServiceNow()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusOverlay = findViewById(R.id.tv_status_overlay)
        statusNotif = findViewById(R.id.tv_status_notif)
        btnToggle = findViewById(R.id.btn_toggle)
        seekOpacity = findViewById(R.id.seek_opacity)
        tvOpacity = findViewById(R.id.tv_opacity_val)
        seekH = findViewById(R.id.seek_h)
        seekS = findViewById(R.id.seek_s)
        seekL = findViewById(R.id.seek_l)
        swHideFab = findViewById(R.id.sw_hidefab)
        llPresets = findViewById(R.id.ll_presets)

        findViewById<Button>(R.id.btn_perm_overlay).setOnClickListener { requestOverlay() }
        btnToggle.setOnClickListener { toggleService() }
        findViewById<Button>(R.id.btn_exit).setOnClickListener {
            if (OverlayService.isRunning(this))
                sendBroadcast(Intent(OverlayService.ACTION_STOP).setPackage(packageName))
            finish()
        }

        seekOpacity.max = 90
        seekOpacity.setOnSeekBarChangeListener(simple { p ->
            val o = p + 5
            Prefs.setOpacity(this, o); tvOpacity.text = "$o%"; applyToService()
        })
        seekH.max = 360; seekS.max = 100; seekL.max = 100
        val hsl = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                hslDragging = true
                Prefs.setColor(this@MainActivity, ColorUtil.hslToRgb(seekH.progress, seekS.progress, seekL.progress))
                applyToService()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { hslDragging = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                hslDragging = false
                Prefs.setSelectedPreset(this@MainActivity, -1)
                buildPresets()
            }
        }
        seekH.setOnSeekBarChangeListener(hsl)
        seekS.setOnSeekBarChangeListener(hsl)
        seekL.setOnSeekBarChangeListener(hsl)
        swHideFab.setOnCheckedChangeListener { _, c -> Prefs.setHideFab(this, c); applyToService() }

        buildPresets()
        findViewById<Button>(R.id.btn_save_slot).setOnClickListener {
            val sel = Prefs.getSelectedPreset(this)
            val target = if (sel >= 0) sel else (0 until 3).firstOrNull { Prefs.isPresetEmpty(this, it) } ?: 0
            Prefs.setPreset(this, target, Prefs.getColor(this))
            Prefs.setSelectedPreset(this, target)
            buildPresets()
            Toast.makeText(this, "已保存到预设${target + 1}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        syncUI()
        buildPresets()
    }

    private fun refreshStatus() {
        statusOverlay.text = "悬浮窗：" +
            if (Settings.canDrawOverlays(this)) "✅ 已授予" else "❌ 未授予（需手动开启）"
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
        swHideFab.isChecked = Prefs.isHideFab(this)
        if (!hslDragging) {
            val (h, s, l) = ColorUtil.rgbToHsl(Prefs.getColor(this))
            seekH.progress = h; seekS.progress = s; seekL.progress = l
        }
    }

    /** 预设区：黑 + 2 个用户槽（共 3 个，全部可修改）。点=应用并选中；长按=把当前色存入该预设。 */
    private fun buildPresets() {
        llPresets.removeAllViews()
        val density = resources.displayMetrics.density
        val sel = Prefs.getSelectedPreset(this)
        for (i in 0 until 3) {
            val col = Prefs.getPreset(this, i)
            val btn = Button(this)
            val lp = LinearLayout.LayoutParams(0, (48 * density).toInt(), 1f).apply {
                setMargins((4 * density).toInt(), 0, (4 * density).toInt(), 0)
            }
            btn.layoutParams = lp
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = (8 * density)
                if (col == null) {
                    setColor(0xFF333333.toInt())
                    btn.text = "＋"
                    btn.setTextColor(0xFFFFFFFF.toInt())
                } else {
                    setColor(col)
                    btn.text = ""
                }
                if (i == sel) setStroke((4 * density).toInt(), 0xFFFFD700.toInt())
                else setStroke((2 * density).toInt(), 0xFF000000.toInt())
            }
            btn.background = bg
            btn.setOnClickListener {
                val c = Prefs.getPreset(this@MainActivity, i)
                if (c != null) {
                    Prefs.setColor(this@MainActivity, c)
                    Prefs.setSelectedPreset(this@MainActivity, i)
                    val (hh, ss, ll) = ColorUtil.rgbToHsl(c)
                    seekH.progress = hh; seekS.progress = ss; seekL.progress = ll
                    applyToService()
                } else {
                    // 空槽：把当前颜色存进去
                    Prefs.setPreset(this@MainActivity, i, Prefs.getColor(this@MainActivity))
                    Prefs.setSelectedPreset(this@MainActivity, i)
                    Toast.makeText(this@MainActivity, "已保存到预设${i + 1}", Toast.LENGTH_SHORT).show()
                }
                buildPresets()
            }
            btn.setOnLongClickListener {
                Prefs.setPreset(this@MainActivity, i, Prefs.getColor(this@MainActivity))
                Prefs.setSelectedPreset(this@MainActivity, i)
                buildPresets()
                Toast.makeText(this@MainActivity, "已保存到预设${i + 1}", Toast.LENGTH_SHORT).show()
                true
            }
            llPresets.addView(btn)
        }
    }

    private fun requestOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } else Toast.makeText(this, "悬浮窗权限已授予", Toast.LENGTH_SHORT).show()
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
        // 关键修复：Android 13+ 必须在 POST_NOTIFICATIONS 已授予后启动前台服务，
        // 否则 startForeground() 的通知会被系统静默丢弃（看不见通知，但蒙版照常运行）。
        // 旧写法在 requestPermissions 异步弹窗未确定时就 startForegroundService，导致通知丢失。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        startOverlayServiceNow()
    }

    /** 真正启动前台服务（前提：通知权限已就绪 / 或不需要）。 */
    private fun startOverlayServiceNow() {
        val i = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
        Prefs.setEnabled(this, true)
        Handler(Looper.getMainLooper()).postDelayed({ refreshStatus() }, 600)
    }

    /** 跳转系统通知设置页，便于手动开启本项目通知。 */
    private fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
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
