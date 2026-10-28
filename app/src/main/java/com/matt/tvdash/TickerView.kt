package com.matt.tvdash

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Choreographer
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import coil.load

/**
 * AASN-style score ticker: a strip of game cards that scrolls forever.
 * Each card: status line (start time, ● LIVE + period, or FINAL),
 * then away and home rows with logo, abbreviation and record or score.
 * The loser is dimmed on a final.
 */
class TickerView(ctx: Context) : HorizontalScrollView(ctx) {

    private val accent = Theme.burgundy
    private val cardBg = Theme.tile
    private val cardBorder = Theme.steel
    private val dim = Color.parseColor("#7D8BA6")
    private val live = Color.parseColor("#3ECF6A")
    private val final = Color.parseColor("#E8B04B")

    private val track = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
    private val d = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    private var half = 0
    private var running = false
    private val speed = 1.5f * d          // pixels per frame
    private var acc = 0f

    init {
        isHorizontalScrollBarEnabled = false
        isFocusable = false
        isFocusableInTouchMode = false
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        addView(track)
    }

    fun setItems(items: List<TickerItem>) {
        track.removeAllViews()
        if (items.isEmpty()) {
            track.addView(label("No games today", Color.WHITE, 16f, false).apply { setPadding(dp(16), dp(20), dp(16), dp(20)) })
            half = 0
            return
        }
        // Two copies back to back so the loop is seamless
        repeat(2) { items.forEach { track.addView(if (it is TickerItem.League) leagueCard(it) else gameCard(it as TickerItem.Game)) } }
        track.post { half = track.width / 2 }
        start()
    }

    // ---------- Cards ----------
    private fun leagueCard(l: TickerItem.League) = label(l.name, Color.WHITE, 18f, true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(18), 0, dp(18), 0)
        background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(accent) }
        layoutParams = LinearLayout.LayoutParams(-2, dp(92)).apply { setMargins(dp(6), 0, dp(6), 0) }
    }

    private fun gameCard(g: TickerItem.Game): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                if (g.isFav) { setColor(Theme.tileFocus); setStroke(dp(3), Color.WHITE) }
                else { setColor(cardBg); setStroke(dp(1), cardBorder) }
            }
            layoutParams = LinearLayout.LayoutParams(dp(170), dp(92)).apply { setMargins(dp(6), 0, dp(6), 0) }
        }
        val statusColor = when (g.state) { "live" -> live; "final" -> final; else -> dim }
        card.addView(label(g.status, statusColor, 12f, g.state != "pre").apply { typeface = Typeface.MONOSPACE })
        val a = g.away.score; val h = g.home.score
        card.addView(teamRow(g.away, g.state == "final" && a != null && h != null && a < h))
        card.addView(teamRow(g.home, g.state == "final" && a != null && h != null && h < a))
        return card
    }

    private fun teamRow(t: TeamLine, loser: Boolean): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            alpha = if (loser) 0.45f else 1f
        }
        row.addView(ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(8) }
            if (t.logo != null) load(t.logo)
        })
        row.addView(label(t.abbr, Color.WHITE, 16f, true), LinearLayout.LayoutParams(0, -2, 1f))
        if (t.score != null) row.addView(label(t.score.toString(), Color.WHITE, 18f, true).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) })
        else row.addView(label(t.record, dim, 12f, false).apply { typeface = Typeface.MONOSPACE })
        return row
    }

    private fun label(s: String, color: Int, size: Float, bold: Boolean) = TextView(context).apply {
        text = s; setTextColor(color); textSize = size; maxLines = 1
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    // ---------- Endless scroll ----------
    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(t: Long) {
            if (!running) return
            if (half > width) {
                acc += speed
                val step = acc.toInt(); acc -= step
                var x = scrollX + step
                if (x >= half) x -= half
                scrollTo(x, 0)
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun start() {
        if (running) return
        running = true
        Choreographer.getInstance().postFrameCallback(frame)
    }

    fun stop() { running = false }

    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }
}
