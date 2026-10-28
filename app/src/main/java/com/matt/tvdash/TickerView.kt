package com.matt.tvdash

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import coil.load

/**
 * Score ticker: a strip of game cards that scrolls sideways forever.
 * Your team's game (put first by Ticker.load) stays fixed at the left while the rest scroll past.
 * Each card: status line (start time, ● LIVE + period, or FINAL), then away and home rows with
 * logo, abbreviation and record or score. The loser is dimmed on a final.
 * Sizes are in "design units" (1/540 of the screen height), so it fits any TV resolution.
 */
class TickerView(ctx: Context, private val unit: Float) : LinearLayout(ctx) {

    private val dim = Color.parseColor("#7D8BA6")
    private val live = Color.parseColor("#3ECF6A")
    private val final = Color.parseColor("#E8B04B")

    private fun dp(v: Int) = (v * unit).toInt()

    private val pinned = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val track = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val scroller = HorizontalScrollView(ctx).apply {
        isHorizontalScrollBarEnabled = false
        isFocusable = false
        overScrollMode = OVER_SCROLL_NEVER
        addView(track)
    }

    private var half = 0
    private var running = false
    private val speed = 0.75f * unit      // pixels per frame
    private var acc = 0f

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = false
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        addView(pinned, LayoutParams(-2, -1))
        addView(scroller, LayoutParams(0, -1, 1f))
    }

    fun setItems(items: List<TickerItem>) {
        pinned.removeAllViews(); track.removeAllViews()
        val fav = items.size >= 2 && (items[1] as? TickerItem.Game)?.isFav == true
        if (fav) items.take(2).forEach { pinned.addView(card(it)) }
        val rest = items.drop(if (fav) 2 else 0)
        if (rest.isEmpty()) {
            if (!fav) track.addView(label("No games today", Color.WHITE, 14f, false).apply { setPadding(dp(8), 0, dp(8), 0) })
            half = 0
            return
        }
        // Two copies back to back so the loop is seamless
        repeat(2) { rest.forEach { track.addView(card(it)) } }
        track.post { half = track.width / 2 }
        start()
    }

    private fun card(i: TickerItem) = if (i is TickerItem.League) leagueCard(i) else gameCard(i as TickerItem.Game)

    // ---------- Endless scroll ----------
    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(t: Long) {
            if (!running) return
            if (half > scroller.width) {
                acc += speed
                val step = acc.toInt(); acc -= step
                var x = scroller.scrollX + step
                if (x >= half) x -= half
                scroller.scrollTo(x, 0)
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun start() {
        if (running) return
        running = true
        Choreographer.getInstance().postFrameCallback(frame)
    }

    override fun onDetachedFromWindow() { running = false; super.onDetachedFromWindow() }

    // ---------- Cards ----------
    private fun leagueCard(l: TickerItem.League) = label(l.name, Color.WHITE, 15f, true).apply {
        gravity = Gravity.CENTER
        background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(Theme.burgundy) }
        layoutParams = LayoutParams(dp(52), dp(64)).apply { setMargins(dp(4), 0, dp(4), 0) }
    }

    private fun gameCard(g: TickerItem.Game): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(7), dp(4), dp(7), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                if (g.isFav) { setColor(Theme.tileFocus); setStroke(dp(2), Color.WHITE) }
                else { setColor(Theme.tile); setStroke(maxOf(1, dp(1) / 2), Theme.steel) }
            }
            layoutParams = LayoutParams(dp(150), dp(64)).apply { setMargins(dp(4), 0, dp(4), 0) }
        }
        val statusColor = when (g.state) { "live" -> live; "final" -> final; else -> dim }
        card.addView(label(g.status, statusColor, 10f, g.state != "pre").apply { typeface = Typeface.MONOSPACE })
        val a = g.away.score; val h = g.home.score
        card.addView(teamRow(g.away, g.state == "final" && a != null && h != null && a < h))
        card.addView(teamRow(g.home, g.state == "final" && a != null && h != null && h < a))
        return card
    }

    private fun teamRow(t: TeamLine, loser: Boolean): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(-1, 0, 1f)
            alpha = if (loser) 0.45f else 1f
        }
        row.addView(ImageView(context).apply {
            layoutParams = LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(6) }
            if (t.logo != null) load(t.logo)
        })
        row.addView(label(t.abbr, Color.WHITE, 13f, true), LayoutParams(0, -2, 1f))
        if (t.score != null) row.addView(label(t.score.toString(), Color.WHITE, 15f, true).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) })
        else row.addView(label(t.record, dim, 10f, false).apply { typeface = Typeface.MONOSPACE })
        return row
    }

    private fun label(s: String, color: Int, size: Float, bold: Boolean) = TextView(context).apply {
        text = s; setTextColor(color); maxLines = 1
        setTextSize(TypedValue.COMPLEX_UNIT_PX, size * unit)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
}
