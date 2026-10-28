package com.matt.tvdash

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.view.View
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Goal celebration: a horn and a flashing red goal light.
 * The horn is generated in code (no recording needed). To use your own sound instead,
 * add it as app/src/main/res/raw/goal_horn.mp3 (or .ogg/.wav). Only the first 10 seconds play.
 */
object GoalFx {
    var forceHorn = false   // set by "Test horn" so the test plays even with the horn switched off

    fun play(ctx: Context, light: View) {
        if (Config.horn || forceHorn) horn(ctx.applicationContext)
        forceHorn = false
        flash(light)
    }

    // Red light pulses six times, like the lamp behind the net
    private fun flash(v: View) {
        v.animate().cancel()
        v.alpha = 0f; v.visibility = View.VISIBLE
        fun pulse(n: Int) {
            if (n == 0) { v.visibility = View.GONE; return }
            v.animate().alpha(0.55f).setDuration(180).withEndAction {
                v.animate().alpha(0f).setDuration(320).withEndAction { pulse(n - 1) }.start()
            }.start()
        }
        pulse(6)
    }

    private fun horn(ctx: Context) {
        val id = ctx.resources.getIdentifier("goal_horn", "raw", ctx.packageName)
        if (id != 0) {
            val mp = MediaPlayer.create(ctx, id) ?: return
            var done = false
            fun finish() { if (!done) { done = true; mp.release() } }
            mp.setOnCompletionListener { finish() }
            mp.start()
            // Long recordings stop after 10 seconds with a 1.5-second fade
            val ui = Handler(Looper.getMainLooper())
            for (step in 1..15) ui.postDelayed({ if (!done) (1f - step / 15f).let { mp.setVolume(it, it) } }, 8_500L + step * 100)
            ui.postDelayed({ finish() }, 10_100)
            return
        }
        thread { try { synthHorn() } catch (_: Exception) { } }
    }

    // A low, brassy 2.5-second blast: a 185 Hz tone with its first six harmonics, soft attack and fade
    private fun synthHorn() {
        val rate = 44_100
        val secs = 2.5
        val n = (rate * secs).toInt()
        val pcm = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val env = min(1.0, t / 0.08) * min(1.0, (secs - t) / 0.4)
            var s = 0.0
            for (h in 1..6) s += sin(2 * PI * 185.0 * h * t) / h
            pcm[i] = (s * env * 0.23 * Short.MAX_VALUE).toInt().coerceIn(-32_767, 32_767).toShort()
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(n * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(pcm, 0, n)
        track.play()
        Thread.sleep((secs * 1000).toLong() + 200)
        track.release()
    }
}
