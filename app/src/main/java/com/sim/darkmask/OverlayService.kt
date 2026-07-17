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
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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

/**
 * 前台服务：负责绘制全屏降亮蒙版 + 可拖动/靠边隐藏的悬浮按钮 + 控制面板。
 * 前台服务常驻：支撑全屏蒙版与可拖动的悬浮按钮。
 */
class OverlayService : Service() {

    companion object {
        const val ACTION_TOGGLE = "com.sim.darkmask.ACTION_TOGGLE"
        const val ACTION_STOP = "com.sim.darkmask.ACTION_STOP"
        const val ACTION_OPEN = "com.sim.darkmask.ACTION_OPEN"
        const val ACTION_APPLY = "com.sim.darkmask.ACTION_APPLY"
        const val ACTION_PANEL = "com.sim.darkmask.ACTION_PANEL"
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
    private var panelRefs: PanelRefs? = null
    private var fabSize = 0
    private var hslDragging = false
    private var presetContainer: LinearLayout? = null

    private val hslListener = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, p: Int, fromUser: Boolean) {
            if (!fromUser) return
            val refs = panelRefs ?: return
            hslDragging = true
            Prefs.setColor(this@OverlayService, ColorUtil.hslToRgb(refs.h.progress, refs.s.progress, refs.l.progress))
            applyAll()
        }
        override fun onStartTrackingTouch(seekBar: SeekBar?) { hslDragging = true }
        override fun onStopTrackingTouch(seekBar: SeekBar?) {
            hslDragging = false
            Prefs.setSelectedPreset(this@OverlayService, -1)
            presetContainer?.let { buildPresetButtons(it) }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.action) {
                ACTION_TOGGLE -> toggleMask()
                ACTION_STOP -> stopSelf()
                ACTION_OPEN -> openSettings()
                ACTION_APPLY -> applyAll()
                ACTION_PANEL -> showPanelExternal()
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
            addAction(ACTION_PANEL)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            registerReceiver(receiver, f, RECEIVER_NOT_EXPORTED)
        else registerReceiver(receiver, f)

        startForeground(NOTIF_ID, buildNotification())
        // 先加悬浮按钮，后加蒙版：蒙版层级更高，盖在悬浮钮之上（悬浮钮仍可被点/拖）。
        createFab()
        createDimView()
        applyAll()
    }

    private fun buildNotification(): Notification {
        // 用新渠道 id：已创建渠道的重要性无法在代码里再升高，换 id 才能让 HIGH 生效。
        val chId = "darkmask_fg_v2"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(chId, "夜深模式运行状态", NotificationManager.IMPORTANCE_HIGH)
            ch.description = "常驻通知：显示运行状态并置顶，点击可打开控制面板"
            ch.setSound(null, null)      // 常驻通知，静音
            ch.enableVibration(false)
            ch.setShowBadge(false)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
        val togglePi = PendingIntent.getBroadcast(
            this, 1, Intent(ACTION_TOGGLE).setPackage(packageName), piFlags())
        val openPi = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), piFlags())
        val stopPi = PendingIntent.getBroadcast(
            this, 3, Intent(ACTION_STOP).setPackage(packageName), piFlags())
        // 点击通知主体：经透明中转 Activity 弹出悬浮控制面板（并自动折叠通知栏）。
        val panelPi = PendingIntent.getActivity(
            this, 4,
            Intent(this, PanelLauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            piFlags())
        val on = Prefs.isEnabled(this)
        return NotificationCompat.Builder(this, chId)
            .setContentTitle("夜深模式${if (on) "· 已开启" else "· 已关闭"}")
            .setContentText("点击弹出控制面板 · 可切换蒙版")
            .setSmallIcon(R.drawable.ic_mask)
            .setOngoing(true)
            .setOnlyAlertOnce(true)                                   // 静默更新，不重复弹出
            .setPriority(NotificationCompat.PRIORITY_HIGH)            // 提升排序，尽量置顶
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)      // 锁屏也可见
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE) // 立即显示，不延迟
            .setContentIntent(panelPi)
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

    // 应用可用区尺寸（不含状态栏/导航栏）——用于悬浮钮定位。
    private fun screenW() = resources.displayMetrics.widthPixels
    private fun screenH() = resources.displayMetrics.heightPixels

    // 物理全屏尺寸（含状态栏、导航栏、刘海区）——用于蒙版铺满整块屏幕。
    private fun realScreenSize(): android.graphics.Point {
        val p = android.graphics.Point()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            p.x = b.width(); p.y = b.height()
        } else {
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(p)
        }
        return p
    }

    // ---- 蒙版视图（物理全屏、覆盖状态栏/刘海、穿透触摸） ----
    private fun createDimView() {
        dimView = View(this).apply { setBackgroundColor(Color.BLACK) }
        val real = realScreenSize()
        // 取两方向最大边做正方形铺满，横竖屏切换也不会露出状态栏；view 不可触摸，超出屏幕部分无副作用。
        val side = if (real.x > real.y) real.x else real.y
        dimParams = WindowManager.LayoutParams(
            side,
            side,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 0
            // 允许绘制进刘海/挖孔区，彻底盖住顶部状态栏。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        wm.addView(dimView, dimParams)
    }

    // ---- 悬浮按钮（可拖动、靠边隐藏） ----
    private fun createFab() {
        val size = (56 * resources.displayMetrics.density).toInt()
        fabSize = size
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
        fabParams!!.x = if (x == -1) (screenW() - size - inset) else x.coerceIn(-size, screenW() - size)
        fabParams!!.y = if (y == -1) (screenH() / 2) else y.coerceIn(0, screenH() - size)

        fab!!.setOnTouchListener(FabTouchListener(size))
        wm.addView(fab, fabParams)
    }

    private inner class FabTouchListener(private val size: Int) : View.OnTouchListener {
        private var startX = 0f; private var startY = 0f
        private var paramX = 0; private var paramY = 0
        private var isClick = true
        private var longFired = false
        private val longPress = Runnable { longFired = true; toggleMask() }

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
                    if (isClick) {
                        openPanel()
                    } else {
                        val cx = fabParams!!.x + size / 2
                        val edge = (size * 0.6f).toInt()
                        when {
                            cx < edge -> snapToEdge(true)
                            cx > screenW() - edge -> snapToEdge(false)
                            else -> {
                                fabParams!!.x = fabParams!!.x.coerceIn(0, screenW() - size)
                                fabParams!!.y = fabParams!!.y.coerceIn(0, screenH() - size)
                                wm.updateViewLayout(fab, fabParams)
                                Prefs.setFabPos(this@OverlayService, fabParams!!.x, fabParams!!.y)
                            }
                        }
                    }
                    return true
                }
            }
            return false
        }
    }

    private fun snapToEdge(left: Boolean) {
        val tab = (fabSize * 0.35f).toInt()
        val from = fabParams!!.x
        val target = if (left) -fabSize + tab else screenW() - tab
        fabParams!!.y = fabParams!!.y.coerceIn(0, screenH() - fabSize)
        Prefs.setFabPos(this, target, fabParams!!.y)
        animateX(from, target)
    }

    private fun animateX(from: Int, to: Int) {
        ValueAnimator.ofInt(from, to).apply {
            duration = 250
            addUpdateListener {
                fabParams!!.x = it.animatedValue as Int
                wm.updateViewLayout(fab, fabParams)
            }
            start()
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

    /** 来自通知点击（PanelLauncherActivity）的"打开控制面板"请求。 */
    private fun showPanelExternal() {
        if (panel == null) openPanel()
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
        val hideFab = root.findViewById<Switch>(R.id.sw_hidefab)
        val close = root.findViewById<Button>(R.id.btn_close)

        panelRefs = PanelRefs(master, opacity, opacityVal, hideFab, h, s, l)
        presetContainer = presets

        opacity.max = 90
        master.setOnCheckedChangeListener { _, c -> Prefs.setEnabled(this, c); applyAll() }
        opacity.setOnSeekBarChangeListener(simple { p ->
            val o = p + 5
            Prefs.setOpacity(this, o); opacityVal.text = "$o%"; applyAll()
        })
        hideFab.setOnCheckedChangeListener { _, c -> Prefs.setHideFab(this, c); applyAll() }
        close.setOnClickListener { closePanel() }
        root.findViewById<Button>(R.id.btn_exit).setOnClickListener { stopSelf() }

        h.setOnSeekBarChangeListener(hslListener)
        s.setOnSeekBarChangeListener(hslListener)
        l.setOnSeekBarChangeListener(hslListener)

        buildPresetButtons(presets)
        saveSlot.setOnClickListener {
            val sel = Prefs.getSelectedPreset(this)
            val target = if (sel >= 0) sel else (0 until 3).firstOrNull { Prefs.isPresetEmpty(this, it) } ?: 0
            Prefs.setPreset(this, target, Prefs.getColor(this))
            Prefs.setSelectedPreset(this, target)
            buildPresetButtons(presets)
            Toast.makeText(this, "已保存到预设${target + 1}", Toast.LENGTH_SHORT).show()
        }
    }

    /** 预设区：黑 + 2 个用户槽（共 3 个，全部可修改）。点=应用并选中；长按=把当前色存入该预设。 */
    private fun buildPresetButtons(container: LinearLayout) {
        container.removeAllViews()
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
                val c = Prefs.getPreset(this@OverlayService, i) ?: return@setOnClickListener
                val refs = panelRefs ?: return@setOnClickListener
                Prefs.setColor(this@OverlayService, c)
                Prefs.setSelectedPreset(this@OverlayService, i)
                syncHsl(c, refs.h, refs.s, refs.l)
                applyAll()
                buildPresetButtons(container)
            }
            btn.setOnLongClickListener {
                Prefs.setPreset(this@OverlayService, i, Prefs.getColor(this@OverlayService))
                Prefs.setSelectedPreset(this@OverlayService, i)
                buildPresetButtons(container)
                Toast.makeText(this@OverlayService, "已保存到预设${i + 1}", Toast.LENGTH_SHORT).show()
                true
            }
            container.addView(btn)
        }
    }

    private fun updatePanelUI() {
        val refs = panelRefs ?: return
        refs.master.isChecked = Prefs.isEnabled(this)
        val o = Prefs.getOpacity(this)
        refs.opacity.progress = o - 5
        refs.opacityVal.text = "$o%"
        refs.hideFab.isChecked = Prefs.isHideFab(this)
        if (!hslDragging) syncHsl(Prefs.getColor(this), refs.h, refs.s, refs.l)
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

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        dimView?.let { wm.removeView(it) }
        fab?.let { wm.removeView(it) }
        panel?.let { wm.removeView(it) }
        dimView = null; fab = null; panel = null
    }

    private data class PanelRefs(
        val master: Switch, val opacity: SeekBar, val opacityVal: TextView,
        val hideFab: Switch,
        val h: SeekBar, val s: SeekBar, val l: SeekBar
    )
}
