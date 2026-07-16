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
}
