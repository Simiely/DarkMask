package com.sim.darkmask

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color

/**
 * 轻量偏好存储（SharedPreferences，零依赖）。
 * 所有可调参数：开关 / 颜色 / 透明度 / 自动夜间 / 隐藏悬浮钮 / 悬浮钮位置。
 */
object Prefs {
    private const val NAME = "darkmask_prefs"

    const val KEY_ENABLED = "enabled"
    const val KEY_COLOR = "color"
    const val KEY_OPACITY = "opacity"      // 5..95
    const val KEY_AUTONIGHT = "autonight"
    const val KEY_HIDE_FAB = "hide_fab"
    const val KEY_FAB_X = "fab_x"
    const val KEY_FAB_Y = "fab_y"
    const val PRESET_COUNT = 3
    const val KEY_PRESET0 = "preset0"
    const val KEY_PRESET1 = "preset1"
    const val KEY_PRESET2 = "preset2"
    const val KEY_SELECTED_PRESET = "selected_preset"

    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context) = sp(ctx).getBoolean(KEY_ENABLED, false)
    fun setEnabled(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(KEY_ENABLED, v).apply()

    fun getColor(ctx: Context) = sp(ctx).getInt(KEY_COLOR, Color.BLACK)
    fun setColor(ctx: Context, c: Int) = sp(ctx).edit().putInt(KEY_COLOR, c).apply()

    fun getOpacity(ctx: Context) = sp(ctx).getInt(KEY_OPACITY, 60)
    fun setOpacity(ctx: Context, o: Int) = sp(ctx).edit().putInt(KEY_OPACITY, o.coerceIn(5, 95)).apply()

    fun isAutoNight(ctx: Context) = sp(ctx).getBoolean(KEY_AUTONIGHT, false)
    fun setAutoNight(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(KEY_AUTONIGHT, v).apply()

    fun isHideFab(ctx: Context) = sp(ctx).getBoolean(KEY_HIDE_FAB, false)
    fun setHideFab(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(KEY_HIDE_FAB, v).apply()

    fun getFabX(ctx: Context) = sp(ctx).getInt(KEY_FAB_X, -1)
    fun getFabY(ctx: Context) = sp(ctx).getInt(KEY_FAB_Y, -1)
    fun setFabPos(ctx: Context, x: Int, y: Int) =
        sp(ctx).edit().putInt(KEY_FAB_X, x).putInt(KEY_FAB_Y, y).apply()

    /** 预设颜色：0=黑(默认可改)，1/2=用户槽。null 表示空。 */
    fun getPreset(ctx: Context, i: Int): Int? {
        val raw = sp(ctx).getString(presetKey(i), null)
        if (raw == null) return if (i == 0) Color.BLACK else null
        return raw.toIntOrNull()
    }
    fun setPreset(ctx: Context, i: Int, c: Int) =
        sp(ctx).edit().putString(presetKey(i), c.toString()).apply()
    fun isPresetEmpty(ctx: Context, i: Int) = getPreset(ctx, i) == null

    /** 当前选中的预设下标（-1 表示无）。 */
    fun getSelectedPreset(ctx: Context) = sp(ctx).getInt(KEY_SELECTED_PRESET, 0)
    fun setSelectedPreset(ctx: Context, i: Int) =
        sp(ctx).edit().putInt(KEY_SELECTED_PRESET, i).apply()

    private fun presetKey(i: Int) = when (i) {
        0 -> KEY_PRESET0; 1 -> KEY_PRESET1; else -> KEY_PRESET2
    }
}
