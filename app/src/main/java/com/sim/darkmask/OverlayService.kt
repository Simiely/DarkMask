package com.sim.darkmask

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.util.Calendar

/**
 * 前台服务：负责绘制全屏降亮蒙版 + 可拖动/靠边隐藏的悬浮按钮 + 控制面板。
 * 用前台服务保活，避免被 HyperOS 杀后台。
 */
class OverlayService : Service() {

    companion object {
        const val ACTION_TOGGLE = "com.sim.darkmask.ACTION_TOGGLE"
        const val ACTION_STOP = "com.sim.darkmask.ACTION_STOP"
        const val ACTION_OPEN = "com.sim.darkmask.ACTION_OPEN"
        const val ACTION_APPLY = "com.sim.darkmask.ACTION_APPLY"
        private const val NOTIF_ID = 1001

        /** 服务是否正在运行（用于 MainActivity 判断） */
        fun isRunning(ctx: Context): Boolean {
            val am = ctx.getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
            return am.getRunningServices(Int.MAX_VALUE)
                .any { it.service.className == OverlayService::class.java.name }
        }
    }

    private lateinit var wm: WindowManager
    private var dimView: View? = null
    private var fab: View? = null
    private var fabIcon: ImageView? = null
    private var panel: View? = null
    private var fabParams: WindowManager.LayoutParams? = null
    private var dimParams: WindowManager.LayoutParams? = null
    private var panelParams: WindowManager.LayoutParams? = null

    private val handler = Handler(Looper.getMainLooper())
    private var autoNightRunnable: Runnable? = null
    private var panelRefs: PanelRefs? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.action) {
                ACTION_TOGGLE -> toggleMask()
                ACTION_STOP -> stopSelf()
                ACTION_OPEN -> openSettings()
                ACTION_APPLY -> applyAll()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val f = IntentFilter().apply {
            addAction(ACTION_TOGGLE); addAction(ACTION_STOP)
            addAction(ACTION_OPEN); addAction(ACTION_APPLY)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            registerReceiver(receiver, f, RECEIVER_NOT_EXPORTED)
        else registerReceiver(receiver, f)

        startForeground(NOTIF_ID, buildNotification())
        // 先加悬浮按钮，后加蒙版：蒙版层级更高，盖在悬浮钮之上（悬浮钮仍可被点/拖）。
        createFab()
        createDimView()
        applyAll()
        if (Prefs.isAutoNight(this)) startAutoNight()
    }

    private fun buildNotification(): Notification {
        val chId = "darkmask_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(chId, "深色蒙版", NotificationManager.IMPORTANCE_LOW)
            ch.description = "常驻以保活蒙版服务"
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
        val togglePi = PendingIntent.getBroadcast(this, 1, Intent(ACTION_TOGGLE), piFlags())
        val openPi = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), piFlags())
        val stopPi = PendingIntent.getBroadcast(this, 3, Intent(ACTION_STOP), piFlags())
        val on = Prefs.isEnabled(this)
        return NotificationCompat.Builder(this, chId)
            .setContentTitle("深色蒙版${if (on) "· 已开启" else "· 已关闭"}")
            .setContentText("点击打开设置 · 可切换蒙版")
            .setSmallIcon(R.drawable.ic_mask)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(R.drawable.ic_toggle, "切换", togglePi)
            .addAction(R.drawable.ic_settings, "设置", openPi)
            .addAction(R.drawable.ic_close, "关闭", stopPi)
            .build()
    }

    private fun piFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

    private fun screenW() = resources.displayMetrics.widthPixels
    private fun screenH() = resources.displayMetrics.heightPixels

    // ---- 蒙版视图（全屏、穿透触摸） ----
    private fun createDimView() {
        dimView = View(this).apply { setBackgroundColor(Color.BLACK) }
        dimParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        wm.addView(dimView, dimParams)
    }

    // ---- 悬浮按钮（可拖动、靠边隐藏） ----
    private fun createFab() {
        val size = (56 * resources.displayMetrics.density).toInt()
        fab = LayoutInflater.from(this).inflate(R.layout.fab_button, null)
        fabIcon = fab?.findViewById(R.id.fab_icon)
        fabParams = WindowManager.LayoutParams(
            size, size, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        val inset = (24 * resources.displayMetrics.density).toInt()
        val x = Prefs.getFabX(this); val y = Prefs.getFabY(this)
        fabParams!!.x = if (x < 0) (screenW() - size - inset) else x
        fabParams!!.y = if (y < 0) (screenH() / 2) else y

        fab!!.setOnTouchListener(FabTouchListener(size))
        wm.addView(fab, fabParams)
    }

    private inner class FabTouchListener(private val size: Int) : View.OnTouchListener {
        private var startX = 0f; private var startY = 0f
        private var paramX = 0; private var paramY = 0
        private var isClick = true
        private var longFired = false
        private val longPress = Runnable { longFired = true; openPanel() }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = e.rawX; startY = e.rawY
                    paramX = fabParams!!.x; paramY = fabParams!!.y
                    isClick = true; longFired = false
                    handler.postDelayed(longPress, 450)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - startX; val dy = e.rawY - startY
                    if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) {
                        if (isClick) { isClick = false; handler.removeCallbacks(longPress) }
                        fabParams!!.x = (paramX + dx).toInt()
                        fabParams!!.y = (paramY + dy).toInt()
                        wm.updateViewLayout(fab, fabParams)
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
                    if (longFired) { longFired = false; return true }
                    if (isClick) toggleMask()
                    else {
                        // 自由悬停：夹紧到屏幕内并保存，不再自动收边
                        fabParams!!.x = fabParams!!.x.coerceIn(0, screenW() - size)
                        fabParams!!.y = fabParams!!.y.coerceIn(0, screenH() - size)
                        wm.updateViewLayout(fab, fabParams)
                        Prefs.setFabPos(this@OverlayService, fabParams!!.x, fabParams!!.y)
                    }
                    return true
                }
            }
            return false
        }
    }

    // ---- 控制面板（长按悬浮钮打开） ----
    private fun openPanel() {
        if (panel != null) { closePanel(); return }
        panel = LayoutInflater.from(this).inflate(R.layout.control_panel, null)
        panelParams = WindowManager.LayoutParams(
            (screenW() * 0.92).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }
        wirePanel(panel!!)
        wm.addView(panel, panelParams)
        updatePanelUI()
    }

    private fun closePanel() {
        panel?.let { wm.removeView(it) }
        panel = null
    }

    private fun wirePanel(root: View) {
        val master = root.findViewById<Switch>(R.id.sw_master)
        val opacity = root.findViewById<SeekBar>(R.id.seek_opacity)
        val opacityVal = root.findViewById<TextView>(R.id.tv_opacity_val)
        val presets = root.findViewById<LinearLayout>(R.id.ll_presets)
        val h = root.findViewById<SeekBar>(R.id.seek_h)
        val s = root.findViewById<SeekBar>(R.id.seek_s)
        val l = root.findViewById<SeekBar>(R.id.seek_l)
        val saveSlot = root.findViewById<Button>(R.id.btn_save_slot)
        val autoNight = root.findViewById<Switch>(R.id.sw_autonight)
        val hideFab = root.findViewById<Switch>(R.id.sw_hidefab)
        val close = root.findViewById<Button>(R.id.btn_close)

        opacity.max = 90
        master.setOnCheckedChangeListener { _, c -> Prefs.setEnabled(this, c); applyAll() }
        opacity.setOnSeekBarChangeListener(simple { p ->
            val o = p + 5
            Prefs.setOpacity(this, o); opacityVal.text = "$o%"; applyAll()
        })
        autoNight.setOnCheckedChangeListener { _, c ->
            Prefs.setAutoNight(this, c)
            if (c) startAutoNight() else stopAutoNight()
        }
        hideFab.setOnCheckedChangeListener { _, c -> Prefs.setHideFab(this, c); applyAll() }
        close.setOnClickListener { closePanel() }
        root.findViewById<Button>(R.id.btn_exit).setOnClickListener { stopSelf() }

        val hsl = simple { _ ->
            Prefs.setColor(this, ColorUtil.hslToRgb(h.progress, s.progress, l.progress))
            applyAll()
        }
        h.setOnSeekBarChangeListener(hsl)
        s.setOnSeekBarChangeListener(hsl)
        l.setOnSeekBarChangeListener(hsl)

        buildPresetButtons(presets, h, s, l)
        saveSlot.setOnClickListener {
            val cur = Prefs.getColor(this)
            val idx = (0..2).firstOrNull { Prefs.getSlot(this, it) < 0 } ?: 0
            Prefs.setSlot(this, idx, cur)
            buildPresetButtons(presets, h, s, l)
            Toast.makeText(this, "已保存到槽${idx + 1}", Toast.LENGTH_SHORT).show()
        }

        panelRefs = PanelRefs(master, opacity, opacityVal, autoNight, hideFab, h, s, l)
    }

    /** 预设区：仅保留一个「黑」，再加 3 个用户槽位（点=应用，长按=存当前色）。 */
    private fun buildPresetButtons(container: LinearLayout, h: SeekBar, s: SeekBar, l: SeekBar) {
        container.removeAllViews()
        val black = Button(this).apply {
            text = "黑"
            setOnClickListener { applyColor(Color.BLACK, h, s, l) }
        }
        container.addView(black)
        for (i in 0..2) {
            val col = Prefs.getSlot(this, i)
            val btn = Button(this).apply {
                text = if (col >= 0) "槽${i + 1}" else "＋"
                if (col >= 0) setBackgroundColor(col)
                setOnClickListener { if (col >= 0) applyColor(col, h, s, l) }
                setOnLongClickListener {
                    Prefs.setSlot(this@OverlayService, i, Prefs.getColor(this@OverlayService))
                    buildPresetButtons(container, h, s, l)
                    Toast.makeText(this@OverlayService, "已保存到槽${i + 1}", Toast.LENGTH_SHORT).show()
                    true
                }
            }
            container.addView(btn)
        }
    }

    private fun applyColor(c: Int, h: SeekBar, s: SeekBar, l: SeekBar) {
        Prefs.setColor(this, c)
        syncHsl(c, h, s, l)
        applyAll()
    }

    private fun updatePanelUI() {
        val refs = panelRefs ?: return
        refs.master.isChecked = Prefs.isEnabled(this)
        val o = Prefs.getOpacity(this)
        refs.opacity.progress = o - 5
        refs.opacityVal.text = "$o%"
        refs.autoNight.isChecked = Prefs.isAutoNight(this)
        refs.hideFab.isChecked = Prefs.isHideFab(this)
        syncHsl(Prefs.getColor(this), refs.h, refs.s, refs.l)
    }

    private fun syncHsl(col: Int, h: SeekBar, s: SeekBar, l: SeekBar) {
        val (hh, ss, ll) = ColorUtil.rgbToHsl(col)
        h.progress = hh; s.progress = ss; l.progress = ll
    }

    private fun simple(onProgress: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) onProgress(p) }
        override fun onStartTrackingTouch(s: SeekBar?) {}
        override fun onStopTrackingTouch(s: SeekBar?) {}
    }

    // ---- 统一应用当前设置 ----
    private fun applyAll() {
        val enabled = Prefs.isEnabled(this)
        dimView?.let {
            it.setBackgroundColor(Prefs.getColor(this))
            it.alpha = Prefs.getOpacity(this) / 100f
            it.visibility = if (enabled) View.VISIBLE else View.GONE
        }
        fab?.visibility = if (Prefs.isHideFab(this)) View.GONE else View.VISIBLE
        if (panel != null) updatePanelUI()
    }

    private fun updateNotification() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification())
    }

    private fun toggleMask() {
        Prefs.setEnabled(this, !Prefs.isEnabled(this))
        applyAll()
        updateNotification()
        MaskTileService.update(this)
    }

    private fun openSettings() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ---- 自动夜间 ----
    private fun startAutoNight() {
        if (autoNightRunnable != null) return
        autoNightRunnable = object : Runnable {
            override fun run() {
                applyAutoNight()
                handler.postDelayed(this, 60_000)
            }
        }
        handler.post(autoNightRunnable!!)
    }

    private fun stopAutoNight() {
        autoNightRunnable?.let { handler.removeCallbacks(it) }
        autoNightRunnable = null
    }

    private fun applyAutoNight() {
        val cal = Calendar.getInstance()
        val mins = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val inNight = mins >= 22 * 60 || mins < 7 * 60
        if (Prefs.isEnabled(this) != inNight) {
            Prefs.setEnabled(this, inNight)
            applyAll()
            updateNotification()
            MaskTileService.update(this)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAutoNight()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        dimView?.let { wm.removeView(it) }
        fab?.let { wm.removeView(it) }
        panel?.let { wm.removeView(it) }
        dimView = null; fab = null; panel = null
    }

    private data class PanelRefs(
        val master: Switch, val opacity: SeekBar, val opacityVal: TextView,
        val autoNight: Switch, val hideFab: Switch,
        val h: SeekBar, val s: SeekBar, val l: SeekBar
    )
}
