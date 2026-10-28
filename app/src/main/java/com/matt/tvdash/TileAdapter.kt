package com.matt.tvdash

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load

const val MORE = "\u0000more"   // key of the "More" tile that turns the page

/**
 * A row (or grid) of tiles that never scrolls: it shows as many tiles as fit ([slots]).
 * If there are more, the last slot becomes "More ▸ 1/3", which turns the page.
 * Sizes are in design units (1/540 of the screen height) times [unit].
 */
class TileAdapter(
    private val wDp: Int,
    private val hDp: Int,
    private val unit: Float,
    private val textSize: Float,
    private val textOnly: Boolean = false,
    private val onClick: (Tile) -> Unit,
    private val onLong: (Tile) -> Unit = {}
) : RecyclerView.Adapter<TileAdapter.VH>() {

    private fun px(v: Int) = (v * unit).toInt()

    private var all: List<Tile> = emptyList()
    private var shown: List<Tile> = emptyList()
    private var page = 0
    private var rv: RecyclerView? = null

    /** The full list; only one page of it is on screen */
    var items: List<Tile>
        get() = all
        set(v) { all = v; refresh() }

    /** How many tiles fit on screen */
    var slots = 1000
        set(v) { field = maxOf(2, v); refresh() }

    // Key of the tile being moved, or null
    var moving: String? = null
        set(v) {
            val old = field; field = v
            // Rebind just the affected tile, keeping its view (and focus); the payload stops a cross-fade
            for (k in listOf(old, v)) shown.indexOfFirst { it.key == k }.takeIf { it >= 0 }?.let { notifyItemChanged(it, "lift") }
        }

    private val paged get() = all.size > slots
    private val per get() = if (paged) slots - 1 else slots
    private val pages get() = if (paged) (all.size + per - 1) / per else 1

    fun firstPage() { page = 0 }

    private fun refresh() {
        page = page.coerceIn(0, pages - 1)
        shown = if (!paged) all else all.drop(page * per).take(per) + Tile(MORE, "More ▸\n${page + 1} / $pages")
        notifyDataSetChanged()
    }

    /** Slides the moving tile by [d] places, turning the page when it crosses an edge */
    fun moveBy(d: Int) {
        val i = all.indexOfFirst { it.key == moving }
        if (i < 0) return
        val j = (i + d).coerceIn(0, all.size - 1)
        if (j == i) return
        all = all.toMutableList().apply { add(j, removeAt(i)) }
        if (paged) page = j / per
        refresh()
    }

    private fun nextPage() {
        page = (page + 1) % pages
        refresh()
        rv?.post { rv?.findViewHolderForAdapterPosition(shown.size - 1)?.itemView?.requestFocus() }
    }

    override fun onAttachedToRecyclerView(r: RecyclerView) { rv = r }

    class VH(val root: LinearLayout, val img: ImageView, val label: TextView) : RecyclerView.ViewHolder(root)

    private fun bg(focused: Boolean, lifted: Boolean) = GradientDrawable().apply {
        cornerRadius = px(10).toFloat()
        when {
            lifted -> { setColor(Theme.steel); setStroke(px(3), Color.WHITE) }
            focused -> { setColor(Theme.tileFocus); setStroke(px(2), Color.WHITE) }
            else -> { setColor(Theme.tile); setStroke(px(2), Color.argb(90, 35, 97, 146)) }
        }
    }

    override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
        val root = LinearLayout(p.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = ViewGroup.MarginLayoutParams(px(wDp), px(hDp)).apply { setMargins(px(4), px(4), px(4), px(4)) }
            setPadding(px(6), px(5), px(6), px(5))
            isFocusable = true
            setOnFocusChangeListener { v, f ->
                val lifted = v.tag == moving
                v.background = bg(f, lifted)
                val s = if (lifted) 1.12f else if (f) 1.06f else 1f
                v.animate().scaleX(s).scaleY(s).setDuration(120).start()
            }
        }
        val img = ImageView(p.context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val label = TextView(p.context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize * unit)
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        root.addView(img); root.addView(label)
        return VH(root, img, label)
    }

    override fun onBindViewHolder(h: VH, i: Int) {
        val t = shown[i]
        val more = t.key == MORE
        val lifted = t.key == moving
        h.root.tag = t.key
        h.root.background = bg(h.root.hasFocus(), lifted)
        val s = if (lifted) 1.12f else if (h.root.hasFocus()) 1.06f else 1f
        h.root.scaleX = s; h.root.scaleY = s
        h.label.text = (if (lifted) "⇄ " else "") + (if (t.pinned) "★ ${t.title}" else t.title)
        h.img.dispose()   // cancel any image load still pending from a recycled tile
        h.img.visibility = if (textOnly || more) View.GONE else View.VISIBLE
        when {
            textOnly || more -> h.img.setImageDrawable(null)
            t.icon != null -> h.img.setImageDrawable(t.icon)
            t.iconUrl != null -> h.img.load(t.iconUrl) { error(android.R.drawable.ic_media_play) }
            else -> h.img.setImageResource(android.R.drawable.ic_media_play)
        }
        h.root.setOnClickListener { if (more) nextPage() else onClick(t) }
        h.root.setOnLongClickListener { if (!more) onLong(t); true }
    }

    override fun getItemCount() = shown.size
}
