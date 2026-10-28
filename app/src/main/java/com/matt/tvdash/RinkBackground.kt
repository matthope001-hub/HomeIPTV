package com.matt.tvdash

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

/** Avalanche-inspired colours (no logos or marks, colours only) */
object Theme {
    val burgundy = Color.parseColor("#6F263D")
    val steel = Color.parseColor("#236192")
    val silver = Color.parseColor("#A2AAAD")
    val night = Color.parseColor("#0A1220")
    val ice = Color.parseColor("#EAF2F8")
    val tile = Color.parseColor("#CC101A2B")       // translucent so the rink shows through
    val tileFocus = Color.parseColor("#E66F263D")  // burgundy when focused
}

/**
 * Background: deep burgundy-to-night gradient, a faint top-down hockey rink
 * (centre red line, two blue lines, centre and faceoff circles) and a
 * snowy mountain ridge along the top edge.
 */
class RinkBackground(private val centreIce: Drawable? = null) : Drawable() {

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var base: Shader? = null
    private var glow: Shader? = null

    // Build gradients once per size instead of on every frame
    override fun onBoundsChange(b: Rect) {
        if (b.isEmpty) return
        val w = b.width().toFloat(); val h = b.height().toFloat()
        base = LinearGradient(0f, 0f, w, h, intArrayOf(Theme.burgundy, Color.parseColor("#2A1830"), Theme.night),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        glow = RadialGradient(w / 2, h * 0.55f, w * 0.55f, Color.parseColor("#33236192"), Color.TRANSPARENT, Shader.TileMode.CLAMP)
    }

    override fun draw(c: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (base == null) return

        // Base gradient
        p.style = Paint.Style.FILL
        p.shader = base
        c.drawRect(0f, 0f, w, h, p)

        // Ice glow in the centre
        p.shader = glow
        c.drawRect(0f, 0f, w, h, p)
        p.shader = null

        // Rink outline
        val stroke = h * 0.004f
        p.style = Paint.Style.STROKE
        p.strokeWidth = stroke
        val inset = w * 0.04f
        val rink = RectF(inset, h * 0.12f, w - inset, h * 0.96f)
        p.color = Color.argb(28, 255, 255, 255)
        c.drawRoundRect(rink, h * 0.2f, h * 0.2f, p)

        // Centre red line and blue lines
        p.strokeWidth = stroke * 3
        p.color = Color.argb(40, 200, 40, 70)
        c.drawLine(w / 2, rink.top, w / 2, rink.bottom, p)
        p.color = Color.argb(45, 35, 97, 146)
        c.drawLine(w * 0.36f, rink.top, w * 0.36f, rink.bottom, p)
        c.drawLine(w * 0.64f, rink.top, w * 0.64f, rink.bottom, p)

        // Centre circle and faceoff circles
        p.strokeWidth = stroke
        p.color = Color.argb(35, 255, 255, 255)
        val r = h * 0.11f
        c.drawCircle(w / 2, rink.centerY(), r, p)

        // Your own centre-ice image (optional), faded into the ice
        centreIce?.let { img ->
            val size = r * 1.7f
            val iw = img.intrinsicWidth.takeIf { it > 0 } ?: 1
            val ih = img.intrinsicHeight.takeIf { it > 0 } ?: 1
            val scale = size / maxOf(iw, ih)
            val dw = (iw * scale).toInt(); val dh = (ih * scale).toInt()
            val cx = (w / 2).toInt(); val cy = rink.centerY().toInt()
            img.setBounds(cx - dw / 2, cy - dh / 2, cx + dw / 2, cy + dh / 2)
            img.alpha = 70
            img.draw(c)
        }
        p.color = Color.argb(30, 200, 40, 70)
        for (x in floatArrayOf(w * 0.16f, w * 0.84f)) for (y in floatArrayOf(rink.top + rink.height() * 0.28f, rink.top + rink.height() * 0.72f)) {
            c.drawCircle(x, y, r * 0.85f, p)
        }

        // Goal creases
        p.color = Color.argb(30, 35, 97, 146)
        p.style = Paint.Style.FILL
        c.drawArc(RectF(w * 0.07f - r * 0.4f, rink.centerY() - r * 0.4f, w * 0.07f + r * 0.4f, rink.centerY() + r * 0.4f), -90f, 180f, true, p)
        c.drawArc(RectF(w * 0.93f - r * 0.4f, rink.centerY() - r * 0.4f, w * 0.93f + r * 0.4f, rink.centerY() + r * 0.4f), 90f, 180f, true, p)

        // Mountain ridge along the top
        val ridge = Path().apply {
            moveTo(0f, 0f); lineTo(0f, h * 0.07f)
            val peaks = floatArrayOf(0.08f, 0.045f, 0.16f, 0.085f, 0.26f, 0.03f, 0.37f, 0.08f, 0.5f, 0.05f,
                0.62f, 0.09f, 0.73f, 0.035f, 0.85f, 0.075f, 0.94f, 0.05f, 1f, 0.07f)
            for (i in peaks.indices step 2) lineTo(w * peaks[i], h * peaks[i + 1])
            lineTo(w, 0f); close()
        }
        p.color = Color.argb(70, 162, 170, 173)
        c.drawPath(ridge, p)
    }

    override fun setAlpha(a: Int) {}
    override fun setColorFilter(f: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
}

/** Jersey-style stripe: white / steel blue / white, like an Avalanche sleeve band */
class JerseyStripe : Drawable() {
    private val p = Paint()
    override fun draw(c: Canvas) {
        val w = bounds.width().toFloat(); val h = bounds.height().toFloat()
        p.color = Color.WHITE; c.drawRect(0f, 0f, w, h, p)
        p.color = Theme.steel; c.drawRect(0f, h * 0.25f, w, h * 0.75f, p)
    }
    override fun setAlpha(a: Int) {}
    override fun setColorFilter(f: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
}
