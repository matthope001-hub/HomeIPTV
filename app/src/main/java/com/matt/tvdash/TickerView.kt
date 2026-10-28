package com.matt.tvdash

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import coil.load

/**
 * Score ticker that fits the screen: as many game cards as fit, changing page every 8 seconds.
 * Each card: status line (start time, ● LIVE + period, or FINAL), then away and home rows with
 * logo, abbreviation and record or score. The loser is dimmed on a final.
 * Your team's game (put first by Ticker.load) stays on every page.
 * Sizes are in "design units" (1/540 of the screen height), so it fits any TV resolution.
 */
class TickerView(ctx: Context, private val unit: Float) : LinearLayout(ctx) {

    private val dim = Color.parseColor("#7D8BA6")
    private val live = Color.parseColor("#3ECF6A")
    private val final = Color.parseColor("#E8B04B")

    private fun dp(v: Int) = (v * unit).toInt()

    private var items: List<TickerItem> = emptyList()
    private var pages: List<List<TickerItem>> = emptyList()
    private var page = 0

    private val rotate = object : Runnable {
        override fun run() {
            if (pages.size > 1) {
                page = (page + 1) % pages.size
                animate().alpha(0f).setDuration(250).withEndAction { show(); animate().alpha(1f).setDuration(250).start() }.start()
            }
            postDelayed(this, 8_000)
        }
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = false
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
    }

    fun setItems(list: List<TickerItem>) {
        items = list
        if (width > 0) paginate() else post { paginate() }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (w != ow) post { paginate() }
    }

    override fun onDetachedFromWindow() { removeCallbacks(rotate); super.onDetachedFromWindow() }

    // Width a card takes, margins included
    private fun cardW(i: TickerItem) = dp(if (i is TickerItem.League) 52 else 150) + dp(8)

    private fun paginate() {
        if (width == 0) return
        val pinned = if (items.size >= 2 && (items[1] as? TickerItem.Game)?.isFav == true) items.take(2) else emptyList()
        val room = width - pinned.sumOf { cardW(it) }
        val out = mutableListOf<List<TickerItem>>()
        var cur = mutableListOf<TickerItem>()
        var used = 0
        var league: TickerItem.League? = null
        for (item in items.drop(pinned.size)) {
            if (item is TickerItem.League) league = item
            val w = cardW(item)
            if (cur.isNotEmpty() && used + w > room) {
                // Never end a page on a league label; start each page with its league
                val carry = if (cur.last() is TickerItem.League) cur.removeAt(cur.lastIndex) else null
                if (cur.isNotEmpty()) out += pinned + cur
                cur = mutableListOf(); used = 0
                val head = carry ?: if (item is TickerItem.Game) league else null
                if (head != null && head !== item) { cur += head; used += cardW(head) }
            }
            cur += item; used += w
        }
        if (cur.isNotEmpty()) out += pinned + cur
        if (out.isEmpty() && pinned.isNotEmpty()) out += pinned
        pages = out
        page = page.coerceIn(0, maxOf(0, pages.size - 1))
        show()
        removeCallbacks(rotate); postDelayed(rotate, 8_000)
    }

    private fun show() {
        removeAllViews()
        val p = pages.getOrNull(page)
        if (p == null) {
            addView(label("No games today", Color.WHITE, 14f, false).apply { setPadding(dp(8), 0, dp(8), 0) })
            return
        }
        p.forEach { addView(if (it is TickerItem.League) leagueCard(it) else gameCard(it as TickerItem.Game)) }
        if (pages.size > 1) addView(label("${page + 1}/${pages.size}", dim, 10f, false).apply { setPadding(dp(4), 0, 0, 0) })
    }

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
