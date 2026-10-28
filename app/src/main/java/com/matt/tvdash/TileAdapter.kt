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

    var items: List<Tile> = emptyList()
        set(v) { field = v; notifyDataSetChanged() }

    class VH(val root: LinearLayout, val img: ImageView, val label: TextView) : RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
        val d = p.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val bg = { focused: Boolean ->
            GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(if (focused) Theme.tileFocus else Theme.tile)
                setStroke(dp(3), if (focused) Color.WHITE else Color.argb(90, 35, 97, 146))
            }
        }
        val root = LinearLayout(p.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = ViewGroup.MarginLayoutParams(dp(wDp), dp(hDp)).apply { setMargins(dp(8), dp(8), dp(8), dp(8)) }
            setPadding(dp(10), dp(10), dp(10), dp(10))
            isFocusable = true
            background = bg(false)
            setOnFocusChangeListener { v, f ->
                v.background = bg(f)
                v.animate().scaleX(if (f) 1.08f else 1f).scaleY(if (f) 1.08f else 1f).setDuration(120).start()
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
        val t = items[i]
        h.label.text = if (t.pinned) "★ ${t.title}" else t.title
        h.img.dispose()   // cancel any image load still pending from a recycled tile
        when {
            t.icon != null -> h.img.setImageDrawable(t.icon)
            t.iconUrl != null -> h.img.load(t.iconUrl) { error(android.R.drawable.ic_media_play) }
            else -> h.img.setImageResource(android.R.drawable.ic_media_play)
        }
        h.root.setOnClickListener { onClick(t) }
        h.root.setOnLongClickListener { onLong(t); true }
    }

    override fun getItemCount() = items.size
}
