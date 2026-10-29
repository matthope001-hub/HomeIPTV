package com.matt.tvdash

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

// ---- Change or add free, legal playlists here ----
const val PLAYLIST_URL = "https://iptv-org.github.io/iptv/countries/ca.m3u"
// XMLTV guide (.xml or .xml.gz). Leave blank to use the playlist's own x-tvg-url header.
const val EPG_URL = ""
// Every English-language channel iptv-org lists; used for "English channels only"
const val ENGLISH_URL = "https://iptv-org.github.io/iptv/languages/eng.m3u"

// Live settings: the defaults above, changed from the phone setup page
object Config {
    @Volatile var playlists: List<String> = listOf(PLAYLIST_URL)
    @Volatile var epg: String = EPG_URL
    @Volatile var team: String = "COL"
    @Volatile var horn: Boolean = true
    @Volatile var english: Boolean = true
    @Volatile var theme: String = "avalanche"
    @Volatile var layout: String = "classic"
    @Volatile var gameNight: Boolean = true   // switch to Jumbotron while your team plays
    fun load(p: Prefs) {
        playlists = p.playlists().ifEmpty { listOf(PLAYLIST_URL) }
        epg = p.str("epg", EPG_URL)
        team = p.str("team", "COL")
        horn = p.str("horn", "on") == "on"
        english = p.str("english", "on") == "on"
        theme = p.str("theme", "avalanche")
        layout = p.str("layout", "classic")
        gameNight = p.str("gamenight", "on") == "on"
        Theme.apply(theme)
    }
}

// Network read with timeouts so a dead host can't hang a thread forever
fun openStream(url: String): InputStream = URL(url).openConnection().run {
    connectTimeout = 10_000; readTimeout = 30_000
    if (url.endsWith(".gz")) GZIPInputStream(getInputStream()) else getInputStream()
}

fun httpGet(url: String): String = openStream(url).bufferedReader().use { it.readText() }

data class Channel(val name: String, val group: String, val logo: String?, val url: String, val tvgId: String?, val lang: String? = null)

data class Tile(
    val key: String,
    val title: String,
    val icon: Drawable? = null,
    val iconUrl: String? = null,
    val pinned: Boolean = false
)

object Session {
    var channels: List<Channel> = emptyList()   // list currently shown, used by the player
    var dead: Set<String> = emptySet()          // stream URLs that didn't answer
}

object M3U {
    var epgUrl: String? = null

    /** Loads every playlist and merges them; fails only if none load */
    fun loadAll(urls: List<String>): List<Channel> {
        var error: Exception? = null
        val out = urls.flatMap { u -> try { load(u) } catch (e: Exception) { error = e; emptyList() } }
        if (out.isEmpty()) error?.let { throw it }
        return out.distinctBy { it.url }
    }

    fun load(url: String = PLAYLIST_URL): List<Channel> {
        val text = httpGet(url)
        val out = mutableListOf<Channel>()
        var info: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTM3U") ->
                    epgUrl = (attr(line, "x-tvg-url") ?: attr(line, "url-tvg"))?.split(",")?.first()?.trim()?.ifBlank { null }
                line.startsWith("#EXTINF") -> info = line
                line.isNotEmpty() && !line.startsWith("#") && info != null -> {
                    val name = info.substringAfterLast(",").trim()
                    val group = attr(info, "group-title")?.split(";")?.first()?.ifBlank { null } ?: "Other"
                    out += Channel(name, group, attr(info, "tvg-logo")?.ifBlank { null }, line, attr(info, "tvg-id")?.ifBlank { null },
                        attr(info, "tvg-language")?.ifBlank { null })
                    info = null
                }
            }
        }
        return out
    }

    /**
     * Keeps English channels: ones whose tvg-language says English, or whose stream is on iptv-org's
     * English list. If that list can't be loaded, nothing is removed.
     */
    fun englishOnly(list: List<Channel>): List<Channel> {
        val keepEpg = epgUrl   // loading the English list must not replace the guide link
        val english = try { load(ENGLISH_URL).map { it.url }.toSet() } catch (_: Exception) { emptySet() }
        epgUrl = keepEpg
        if (english.isEmpty()) return list
        return list.filter { it.url in english || it.lang?.contains("English", ignoreCase = true) == true }
    }

    private fun attr(s: String, key: String): String? =
        Regex("$key=\"([^\"]*)\"").find(s)?.groupValues?.get(1)
}

// ---------- TV guide (XMLTV) ----------
data class Prog(val start: Long, val stop: Long, val title: String)

object Epg {
    @Volatile var guide: Map<String, List<Prog>> = emptyMap()
    private val fmt = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)

    /** Streams the file and keeps only our channels, from 1 hour ago to 12 hours ahead */
    fun load(url: String, ids: Set<String>) {
        val now = System.currentTimeMillis()
        val from = now - 3_600_000; val to = now + 12 * 3_600_000
        val out = HashMap<String, MutableList<Prog>>()
        openStream(url).use { input ->
            val p = Xml.newPullParser()
            p.setInput(input, null)
            var ch: String? = null; var start = 0L; var stop = 0L; var title: String? = null
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "programme" -> {
                            ch = p.getAttributeValue(null, "channel")?.takeIf { it in ids }
                            start = time(p.getAttributeValue(null, "start"))
                            stop = time(p.getAttributeValue(null, "stop"))
                            title = null
                        }
                        "title" -> if (ch != null && title == null) title = p.nextText()
                    }
                } else if (p.eventType == XmlPullParser.END_TAG && p.name == "programme") {
                    val c = ch; val t = title
                    if (c != null && t != null && stop > from && start < to) out.getOrPut(c) { mutableListOf() } += Prog(start, stop, t)
                    ch = null
                }
            }
        }
        guide = out.mapValues { e -> e.value.sortedBy { it.start } }
    }

    private fun time(s: String?) = try { fmt.parse(s!!.trim())!!.time } catch (_: Exception) { 0L }

    /** What's on now and what's next for a channel */
    fun nowNext(id: String?): Pair<Prog?, Prog?> {
        val list = guide[id ?: return null to null] ?: return null to null
        val now = System.currentTimeMillis()
        val i = list.indexOfFirst { now < it.stop }
        if (i < 0) return null to null
        val cur = list[i].takeIf { it.start <= now }
        return cur to list.getOrNull(if (cur != null) i + 1 else i)
    }
}

// ---------- Dead stream check ----------
object StreamCheck {
    /** Returns the URLs that don't answer. Runs 8 checks at a time. */
    fun run(list: List<Channel>): Set<String> {
        val dead = Collections.synchronizedSet(HashSet<String>())
        val pool = Executors.newFixedThreadPool(8)
        list.forEach { ch -> pool.execute { if (!alive(ch.url)) dead += ch.url } }
        pool.shutdown()
        pool.awaitTermination(10, TimeUnit.MINUTES)
        return dead.toSet()
    }

    private fun alive(url: String): Boolean {
        if (!url.startsWith("http")) return true   // can't test rtmp etc. this way, leave them shown
        return try {
            (URL(url).openConnection() as HttpURLConnection).run {
                connectTimeout = 5_000; readTimeout = 5_000; instanceFollowRedirects = true
                val ok = responseCode in 200..399
                disconnect(); ok
            }
        } catch (_: Exception) { false }
    }
}

/** Puts items in a saved order; anything not in it keeps its place after them */
fun <T> List<T>.ordered(order: List<String>, key: (T) -> String): List<T> {
    if (order.isEmpty()) return this
    val pos = order.withIndex().associate { it.value to it.index }
    return sortedBy { pos[key(it)] ?: Int.MAX_VALUE }
}

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("tvdash", Context.MODE_PRIVATE)
    fun get(set: String): Set<String> = sp.getStringSet(set, emptySet()) ?: emptySet()
    fun toggle(set: String, key: String): Boolean {
        val s = get(set).toMutableSet()
        val added = if (key in s) { s.remove(key); false } else { s.add(key); true }
        sp.edit().putStringSet(set, s).apply()
        return added
    }

    // Settings from the phone setup page
    fun playlists(): List<String> = str("playlists", "").split('\n').filter { it.isNotBlank() }
    fun str(key: String, def: String): String = sp.getString(key, def) ?: def
    fun put(key: String, value: String) = sp.edit().putString(key, value).apply()
    fun saveSettings(playlists: List<String>, epg: String, team: String, horn: Boolean, english: Boolean) =
        sp.edit().putString("playlists", playlists.joinToString("\n")).putString("epg", epg).putString("team", team)
            .putString("horn", if (horn) "on" else "off").putString("english", if (english) "on" else "off").apply()

    // Tile order you set with Move, per row
    fun order(row: String): List<String> = str("order_$row", "").split('\n').filter { it.isNotEmpty() }
    fun setOrder(row: String, keys: List<String>) = sp.edit().putString("order_$row", keys.joinToString("\n")).apply()

    // Last 10 channels watched, newest first
    fun recent(): List<String> = sp.getString("recent", "").orEmpty().split('\n').filter { it.isNotEmpty() }
    fun pushRecent(url: String) {
        val list = (listOf(url) + recent().filter { it != url }).take(10)
        sp.edit().putString("recent", list.joinToString("\n")).apply()
    }
}
