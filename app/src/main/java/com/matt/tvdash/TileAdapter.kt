package com.matt.tvdash

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load

class TileAdapter(
    private val wDp: Int,
    private val hDp: Int,
    private val textOnly: Boolean = false,
    private val onClick: (Tile) -> Unit,
    private val onLong: (Tile) -> Unit = {}
) : RecyclerView.Adapter<TileAdapter.VH>() {

    private var list: List<Tile> = emptyList()
    var items: List<Tile>
        get() = list
        set(v) { list = v; notifyDataSetChanged() }

    // Key of the tile being moved, or null
    var moving: String? = null
        set(v) {
            val old = field; field = v
            // Rebind just the affected tile, keeping its view (and focus); the payload stops a cross-fade
            for (k in listOf(old, v)) list.indexOfFirst { it.key == k }.takeIf { it >= 0 }?.let { notifyItemChanged(it, "lift") }
        }

    /** Slides the moving tile by [d] places; returns its new position */
    fun moveBy(d: Int): Int {
        val i = list.indexOfFirst { it.key == moving }
        if (i < 0) return -1
        val j = (i + d).coerceIn(0, list.size - 1)
        if (j != i) {
            list = list.toMutableList().apply { add(j, removeAt(i)) }
            notifyItemMoved(i, j)
        }
        return j
    }

    class VH(val root: LinearLayout, val img: ImageView, val label: TextView) : RecyclerView.ViewHolder(root)

    private fun bg(v: View, focused: Boolean, lifted: Boolean): GradientDrawable {
        val d = v.resources.displayMetrics.density
        return GradientDrawable().apply {
            cornerRadius = 14 * d
            when {
                lifted -> { setColor(Theme.steel); setStroke((5 * d).toInt(), Color.WHITE) }
                focused -> { setColor(Theme.tileFocus); setStroke((3 * d).toInt(), Color.WHITE) }
                else -> { setColor(Theme.tile); setStroke((3 * d).toInt(), Color.argb(90, 35, 97, 146)) }
            }
        }
    }

    override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
        val d = p.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val root = LinearLayout(p.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = ViewGroup.MarginLayoutParams(dp(wDp), dp(hDp)).apply { setMargins(dp(8), dp(8), dp(8), dp(8)) }
            setPadding(dp(10), dp(10), dp(10), dp(10))
            isFocusable = true
            setOnFocusChangeListener { v, f ->
                val lifted = v.tag == moving
                v.background = bg(v, f, lifted)
                val s = if (lifted) 1.15f else if (f) 1.08f else 1f
                v.animate().scaleX(s).scaleY(s).setDuration(120).start()
            }
        }
        val img = ImageView(p.context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = if (textOnly) View.GONE else View.VISIBLE
        }
        val label = TextView(p.context).apply {
            setTextColor(Color.WHITE)
            textSize = if (textOnly) 18f else 14f
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        root.addView(img); root.addView(label)
        return VH(root, img, label)
    }

    override fun onBindViewHolder(h: VH, i: Int) {
        val t = list[i]
        val lifted = t.key == moving
        h.root.tag = t.key
        h.root.background = bg(h.root, h.root.hasFocus(), lifted)
        val s = if (lifted) 1.15f else if (h.root.hasFocus()) 1.08f else 1f
        h.root.scaleX = s; h.root.scaleY = s
        h.label.text = (if (lifted) "⇄ " else "") + (if (t.pinned) "★ ${t.title}" else t.title)
        h.img.dispose()   // cancel any image load still pending from a recycled tile
        when {
            t.icon != null -> h.img.setImageDrawable(t.icon)
            t.iconUrl != null -> h.img.load(t.iconUrl) { error(android.R.drawable.ic_media_play) }
            else -> h.img.setImageResource(android.R.drawable.ic_media_play)
        }
        h.root.setOnClickListener { onClick(t) }
        h.root.setOnLongClickListener { onLong(t); true }
    }

    override fun getItemCount() = list.size
}
