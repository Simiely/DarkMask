package com.sim.darkmask

import android.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * HSL <-> RGB 转换工具。
 * rgbToHsl 返回 H∈[0,360], S∈[0,100], L∈[0,100]；
 * hslToRgb 接受同样的区间，返回 android.graphics.Color 整数。
 */
object ColorUtil {

    fun rgbToHsl(color: Int): Triple<Int, Int, Int> {
        val r = Color.red(color) / 255f
        val g = Color.green(color) / 255f
        val b = Color.blue(color) / 255f
        val mx = max(r, g, b)
        val mn = min(r, g, b)
        val l = (mx + mn) / 2f
        var h = 0f
        var s = 0f
        if (mx != mn) {
            val d = mx - mn
            s = if (l > 0.5f) d / (2f - mx - mn) else d / (mx + mn)
            h = when (mx) {
                r -> (g - b) / d + (if (g < b) 6f else 0f)
                g -> (b - r) / d + 2f
                else -> (r - g) / d + 4f
            }
            h /= 6f
        }
        return Triple(
            (h * 360).roundToInt().coerceIn(0, 360),
            (s * 100).roundToInt().coerceIn(0, 100),
            (l * 100).roundToInt().coerceIn(0, 100)
        )
    }

    fun hslToRgb(h: Int, s: Int, l: Int): Int {
        val hh = h.coerceIn(0, 360) / 360f
        val ss = s.coerceIn(0, 100) / 100f
        val ll = l.coerceIn(0, 100) / 100f
        if (ss == 0f) {
            val v = (ll * 255).roundToInt().coerceIn(0, 255)
            return Color.rgb(v, v, v)
        }
        val q = if (ll < 0.5f) ll * (1 + ss) else ll + ss - ll * ss
        val p = 2 * ll - q
        val r = hue2rgb(p, q, hh + 1f / 3f)
        val g = hue2rgb(p, q, hh)
        val b = hue2rgb(p, q, hh - 1f / 3f)
        return Color.rgb(
            (r * 255).roundToInt().coerceIn(0, 255),
            (g * 255).roundToInt().coerceIn(0, 255),
            (b * 255).roundToInt().coerceIn(0, 255)
        )
    }

    private fun hue2rgb(p: Float, q: Float, t: Float): Float {
        var tt = t
        if (tt < 0f) tt += 1f
        if (tt > 1f) tt -= 1f
        return when {
            tt < 1f / 6f -> p + (q - p) * 6f * tt
            tt < 1f / 2f -> q
            tt < 2f / 3f -> p + (q - p) * (2f / 3f - tt) * 6f
            else -> p
        }
    }
}
