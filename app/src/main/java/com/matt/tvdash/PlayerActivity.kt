package com.matt.tvdash

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PlayerActivity : AppCompatActivity() {

    private lateinit var player: ExoPlayer
    private lateinit var osd: TextView
    private val ui = Handler(Looper.getMainLooper())
    private var idx = 0
    private var prev = -1            // last channel, for Back
    private var exitAt = 0L          // Back again before this time exits
    private lateinit var prefs: Prefs
    private lateinit var channels: List<Channel>

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = Prefs(this)
        channels = Session.channels   // own copy: the dashboard list can change while we play
        idx = intent.getIntExtra("idx", 0)
        player = ExoPlayer.Builder(this).build()

        val view = PlayerView(this).apply { player = this@PlayerActivity.player; useController = false }
        osd = TextView(this).apply {
            textSize = 26f; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = 16f; setColor(Color.parseColor("#D96F263D")); setStroke(4, Theme.steel) }
            setPadding(40, 20, 40, 20)
        }
        val root = FrameLayout(this)
        root.addView(view)
        root.addView(osd, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(48, 48, 0, 0) })
        setContentView(root)

        player.addListener(object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) { show("Stream offline — press ▲/▼ to change channel") }
        })
        tune()
    }

    private fun tune(extra: String = "") {
        val list = channels
        if (list.isEmpty()) { finish(); return }
        idx = (idx + list.size) % list.size
        val ch = list[idx]
        player.setMediaItem(MediaItem.fromUri(ch.url))
        player.prepare(); player.playWhenReady = true
        prefs.pushRecent(ch.url)
        show(info() + extra)
    }

    private fun go(n: Int) { prev = idx; idx = n; tune() }

    private fun flip() { val t = idx; idx = prev; prev = t; tune("\n◀ Back again to exit") }

    // Channel name plus what's on now and next from the TV guide
    private fun info(): String {
        val ch = channels[idx]
        val (now, next) = Epg.nowNext(ch.tvgId)
        val f = SimpleDateFormat("h:mm a", Locale.getDefault())
        return buildString {
            append("${idx + 1}  ${ch.name}")
            if (now != null) append("\nNow: ${now.title}")
            if (next != null) append("\n${f.format(Date(next.start))}  ${next.title}")
        }
    }

    private fun show(s: String) {
        osd.text = s; osd.alpha = 1f
        ui.removeCallbacksAndMessages(null)
        ui.postDelayed({ osd.animate().alpha(0f).setDuration(400).start() }, 3500)
    }

    override fun onKeyDown(code: Int, e: KeyEvent?): Boolean = when (code) {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { go(idx - 1); true }
        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { go(idx + 1); true }
        KeyEvent.KEYCODE_LAST_CHANNEL -> { if (prev >= 0) flip(); true }
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { show(info()); true }
        // Back jumps to the last channel; Back again within 3 seconds leaves the player
        KeyEvent.KEYCODE_BACK -> {
            if (prev >= 0 && System.currentTimeMillis() > exitAt) { flip(); exitAt = System.currentTimeMillis() + 3_000 }
            else finish()
            true
        }
        else -> super.onKeyDown(code, e)
    }

    override fun onStop() { super.onStop(); player.pause() }
    override fun onStart() { super.onStart(); if (::player.isInitialized) player.play() }
    override fun onDestroy() { super.onDestroy(); player.release() }
}
