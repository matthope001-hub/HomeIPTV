package com.matt.tvdash

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.provider.Settings
import android.view.View
import android.view.WindowManager

/**
 * Silent goal light over other apps (Netflix, YouTube…): the screen edges pulse red six times.
 * Needs "Display over other apps", which Google TV hides; INSTALL.md has the one adb command.
 */
object EdgeGlow {
    @Volatile var allowed = false   // refreshed by MainActivity, shown on the phone setup page

    fun check(ctx: Context) { allowed = Settings.canDrawOverlays(ctx) }

    fun show(ctx: Context) {
        check(ctx)
        if (!allowed) return
        val wm = ctx.getSystemService(WindowManager::class.java)
        val v = GlowView(ctx)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT)
        try { wm.addView(v, lp) } catch (_: Exception) { return }
        v.alpha = 0f
        fun pulse(n: Int) {
            if (n == 0) { try { wm.removeView(v) } catch (_: Exception) { }; return }
            v.animate().alpha(1f).setDuration(220).withEndAction {
                v.animate().alpha(0f).setDuration(380).withEndAction { pulse(n - 1) }.start()
            }.start()
        }
        pulse(6)
    }

    // Red glow fading in from all four edges; the middle of the picture stays clear
    private class GlowView(ctx: Context) : View(ctx) {
        private val p = Paint()
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat(); val t = h * 0.12f
            val red = Color.parseColor("#E6C8102E")
            fun band(x0: Float, y0: Float, x1: Float, y1: Float, l: Float, tp: Float, r: Float, b: Float) {
                p.shader = LinearGradient(x0, y0, x1, y1, red, Color.TRANSPARENT, Shader.TileMode.CLAMP)
                c.drawRect(l, tp, r, b, p)
            }
            band(0f, 0f, 0f, t, 0f, 0f, w, t)            // top
            band(0f, h, 0f, h - t, 0f, h - t, w, h)      // bottom
            band(0f, 0f, t, 0f, 0f, 0f, t, h)            // left
            band(w, 0f, w - t, 0f, w - t, 0f, w, h)      // right
        }
    }
}
