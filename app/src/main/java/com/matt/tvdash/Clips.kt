package com.matt.tvdash

import org.json.JSONObject
import java.net.URL

/**
 * Turns an NHL highlight link into a video TV Dash's own player can play, so pressing OK on a
 * goal just plays it. NHL clips are hosted on Brightcove: the clip page names the Brightcove
 * account and player, the player's public config holds the key, and the playback API returns
 * the video files. Returns null if any step fails (the caller then falls back to the link).
 */
object Clips {
    fun resolve(page: String, clipId: Long?): String? = try {
        val html = httpGet(page)
        val (account, player) = Regex("""players\.brightcove\.net/(\d+)/([\w-]+?)_default""").find(html)?.destructured
            ?: throw IllegalStateException("no player on page")
        val id = clipId?.takeIf { it > 0 }?.toString()
            ?: Regex("""data-video-id=["'](\d+)""").find(html)?.groupValues?.get(1)
            ?: Regex(""""videoId"\s*:\s*"?(\d{10,})""").find(html)?.groupValues?.get(1)
            ?: throw IllegalStateException("no video id")
        val key = JSONObject(httpGet("https://players.brightcove.net/$account/${player}_default/config.json"))
            .optJSONObject("video_cloud")?.optString("policy_key")?.ifBlank { null }
            ?: throw IllegalStateException("no policy key")
        val c = URL("https://edge.api.brightcove.com/playback/v1/accounts/$account/videos/$id").openConnection()
        c.setRequestProperty("Accept", "application/json;pk=$key")
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        val sources = JSONObject(c.getInputStream().bufferedReader().use { it.readText() }).getJSONArray("sources")
        val list = (0 until sources.length()).map { sources.getJSONObject(it) }.filter { it.optString("src").startsWith("https") }
        // Prefer HLS (adapts to the connection), then MP4
        (list.firstOrNull { it.optString("type").contains("mpegurl", ignoreCase = true) }
            ?: list.firstOrNull { it.optString("container").equals("MP4", ignoreCase = true) }
            ?: list.firstOrNull())?.optString("src")
    } catch (_: Exception) { null }
}
