package com.matt.tvdash

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Dashboard layouts, picked from the phone: Classic (the rink), Spotlight (big featured card),
 * Jumbotron (arena scoreboard) and Jersey (bold team look). Goal alerts, the ticker, the
 * screensaver and the setup panel sit on top of whichever layout is showing, so they work in all.
 */
object Look {
    val layouts = listOf("classic", "spotlight", "jumbotron", "jersey")
    @Volatile var gameLive = false   // your team is playing right now (from the scores)

    /** The layout to draw: Jumbotron while your team plays if the game-night switch is on */
    fun current(): String =
        if (Config.gameNight && gameLive) "jumbotron" else Config.layout.takeIf { it in layouts } ?: "classic"

    fun label(n: String) = n.replaceFirstChar { it.uppercase() }

    fun background(layout: String): Drawable = when (layout) {
        "spotlight" -> ColorDrawable(Theme.night)
        "jumbotron" -> ColorDrawable(Color.parseColor("#050505"))
        else -> JerseyBackground()
    }

    val amber = Color.parseColor("#FFB000")
    val condensed: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
}

/** Jersey layout background: accent colour, a sleeve stripe low down and your team's letters, faded */
class JerseyBackground : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(c: Canvas) {
        val w = bounds.width().toFloat(); val h = bounds.height().toFloat()
        c.drawColor(Theme.burgundy)
        p.color = Color.argb(22, 255, 255, 255); p.textSize = h * 0.62f; p.typeface = Look.condensed; p.textAlign = Paint.Align.RIGHT
        c.drawText(Config.team, w * 0.97f, h * 0.62f, p)
        val top = h * 0.66f; val band = h * 0.07f
        p.color = Color.WHITE; c.drawRect(0f, top, w, top + band, p)
        p.color = Theme.steel; c.drawRect(0f, top + band * 0.22f, w, top + band * 0.78f, p)
    }
    override fun setAlpha(a: Int) {}
    override fun setColorFilter(f: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
}

/** The big panel a modern layout adds at the top, filled from your team's game */
class Hero(private val ctx: Context, private val unit: Float, private val layout: String, photo: Drawable?) {
    val height = when (layout) { "spotlight" -> 150; "jumbotron" -> 128; else -> 118 }   // design units
    val view: View
    private var pill: TextView? = null
    private var title: TextView? = null
    private var sub: TextView? = null
    private var away: TextView? = null
    private var score: TextView? = null
    private var home: TextView? = null
    private var period: TextView? = null

    private fun dp(v: Int) = (v * unit).toInt()
    private fun tv(size: Float, color: Int = Color.WHITE, face: Typeface = Typeface.DEFAULT_BOLD) = TextView(ctx).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, size * unit); setTextColor(color); typeface = face; maxLines = 1
    }

    init {
        view = when (layout) {
            "spotlight" -> spotlight(photo)
            "jumbotron" -> jumbotron()
            else -> jersey()
        }
        update(null)
    }

    // Big featured card: status pill, the score as a headline, your photo fading in on the right
    private fun spotlight(photo: Drawable?) = FrameLayout(ctx).apply {
        if (photo != null) {
            addView(ImageView(ctx).apply { setImageDrawable(photo); scaleType = ImageView.ScaleType.CENTER_CROP },
                FrameLayout.LayoutParams(dp(330), -1, Gravity.END))
            addView(View(ctx).apply {
                background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Theme.night, Color.TRANSPARENT))
            }, FrameLayout.LayoutParams(dp(200), -1, Gravity.END).apply { marginEnd = dp(130) })
        }
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
            pill = tv(11f).apply { setPadding(dp(8), dp(2), dp(8), dp(2)) }
            title = tv(44f, face = Look.condensed)
            sub = tv(14f, Color.parseColor("#CCD3DE"), Typeface.DEFAULT)
            addView(pill, LinearLayout.LayoutParams(-2, -2)); addView(title); addView(sub)
        }, FrameLayout.LayoutParams(-1, -1))
    }

    // LED scoreboard: amber team letters, red score, period and clock underneath
    private fun jumbotron() = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(Color.parseColor("#0C0C0C")); setStroke(dp(3), Color.parseColor("#2A2A2A")) }
        val mono = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        away = tv(36f, Look.amber, mono).apply { setShadowLayer(10f, 0f, 0f, Color.parseColor("#FF8C00")); gravity = Gravity.CENTER }
        score = tv(62f, Color.parseColor("#FF3B30"), mono).apply { setShadowLayer(14f, 0f, 0f, Color.parseColor("#FF3B30")); gravity = Gravity.CENTER }
        home = tv(36f, Look.amber, mono).apply { setShadowLayer(10f, 0f, 0f, Color.parseColor("#FF8C00")); gravity = Gravity.CENTER }
        period = tv(18f, Look.amber, mono)
        addView(LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(away, LinearLayout.LayoutParams(0, -2, 1f)); addView(score); addView(home, LinearLayout.LayoutParams(0, -2, 1f))
        }, LinearLayout.LayoutParams(-1, -2))
        addView(period)
    }

    // Big condensed headline and a score line
    private fun jersey() = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
        title = tv(56f, face = Look.condensed).apply { letterSpacing = 0.03f }
        sub = tv(18f, Color.parseColor("#F1D5DD"), Typeface.DEFAULT)
        addView(title); addView(sub)
    }

    fun update(g: TickerItem.Game?) {
        val a = g?.away; val h = g?.home
        val line = if (a == null || h == null) null else if (a.score == null) "${a.abbr} @ ${h.abbr}" else "${a.abbr} ${a.score}  ·  ${h.abbr} ${h.score}"
        when (layout) {
            "spotlight" -> {
                pill?.text = when (g?.state) { "live" -> "● LIVE"; "final" -> "FINAL"; "pre" -> "TONIGHT"; else -> "LIVE TV" }
                pill?.background = GradientDrawable().apply { cornerRadius = dp(4).toFloat(); setColor(if (g?.state == "live") Color.parseColor("#C8102E") else Theme.steel) }
                title?.text = line ?: "TV Dash"
                sub?.text = if (g == null) "Free live TV · your apps · scores" else "${g.status}  ·  🚨 ${Config.team} goals has the clips"
            }
            "jumbotron" -> {
                away?.text = a?.abbr ?: Config.team
                home?.text = h?.abbr ?: "---"
                score?.text = if (a?.score != null && h?.score != null) "${a.score} : ${h.score}" else "- : -"
                period?.text = g?.status ?: "NO GAME TODAY"
            }
            else -> {
                title?.text = if (g?.state == "live") "GAME NIGHT" else "TV DASH"
                sub?.text = if (g == null) "Free live TV · your apps · scores" else "$line  ·  ${g.status}"
            }
        }
    }
}
