package com.matt.tvdash

import android.os.Handler
import android.os.Looper
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * Tiny web page on the TV (port 8080) for changing settings from a phone on the same Wi-Fi:
 * playlist links, TV guide link and favourite team.
 */
object SetupServer {
    // 8080 if it's free; otherwise the next free port up to 8090 (some TVs already use 8080)
    var port = 8080
        private set
    var onSaved: (() -> Unit)? = null
    private var server: ServerSocket? = null
    private val ui = Handler(Looper.getMainLooper())

    fun start(prefs: Prefs) {
        if (server != null) return
        val s = (8080..8090).firstNotNullOfOrNull { p -> try { ServerSocket(p).also { port = p } } catch (_: Exception) { null } } ?: return
        server = s
        thread(isDaemon = true) {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) {
                    try { handle(c, prefs) } catch (_: Throwable) { } finally { try { c.close() } catch (_: Exception) { } }
                }
            }
        }
    }

    fun stop() { server?.close(); server = null }

    private fun handle(c: Socket, prefs: Prefs) {
        c.soTimeout = 10_000
        val input = c.getInputStream().buffered()
        val request = readLine(input).split(" ")
        val method = request.getOrElse(0) { "" }
        val path = request.getOrElse(1) { "/" }
        var len = 0
        while (true) {
            val h = readLine(input)
            if (h.isEmpty()) break
            if (h.lowercase().startsWith("content-length:")) len = h.substringAfter(":").trim().toIntOrNull() ?: 0
        }
        val body = if (len in 1..65_536) {
            val buf = ByteArray(len); var n = 0
            while (n < len) { val r = input.read(buf, n, len - n); if (r < 0) break; n += r }
            String(buf, 0, n, Charsets.UTF_8)
        } else ""

        // If building the page fails, send the error text instead of hanging up, so it shows on the phone
        val (status, html) = try {
            "200 OK" to when {
                method == "POST" && path == "/save" -> { save(body, prefs); page("Saved ✓ The TV is reloading channels.") }
                method == "POST" && path == "/test" -> page(testHorn())
                path == "/favicon.ico" -> ""
                else -> page(null)
            }
        } catch (e: Throwable) {
            "500 Internal Server Error" to "<pre>TV Dash setup page error:\n${esc(e.stackTraceToString())}</pre>"
        }
        val bytes = html.toByteArray()
        c.getOutputStream().apply {
            write("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(bytes); flush()
        }
        c.shutdownOutput()   // finish cleanly so the browser gets the whole page before the socket closes
    }

    private fun readLine(i: InputStream): String {
        val sb = StringBuilder()
        while (sb.length < 8_192) {
            val b = i.read()
            if (b < 0 || b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun save(body: String, prefs: Prefs) {
        val form = body.split("&").mapNotNull {
            val k = it.substringBefore("=", ""); if (k.isEmpty()) null
            else k to URLDecoder.decode(it.substringAfter("="), "UTF-8")
        }.toMap()
        val playlists = form["playlists"].orEmpty().lines().map { it.trim() }.filter { it.startsWith("http") }
        val epg = form["epg"].orEmpty().trim().takeIf { it.isEmpty() || it.startsWith("http") }.orEmpty()
        val team = form["team"].orEmpty().trim().uppercase().filter { it.isLetter() }.take(3).ifEmpty { "COL" }
        prefs.saveSettings(playlists, epg, team, form["horn"] == "on")
        Config.load(prefs)
        ui.post { onSaved?.invoke() }
    }

    // Runs a full goal alert (banner, red light, horn) on whatever TV Dash screen is showing
    private fun testHorn(): String {
        if (GoalWatch.listener == null) return "Open TV Dash on the TV first, then press Test horn again."
        ui.post { GoalFx.forceHorn = true; GoalWatch.listener?.invoke("Test from your phone") }
        return "Playing the goal horn on the TV."
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun page(msg: String?) = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>TV Dash setup</title>
<style>
body{margin:0;font-family:system-ui,sans-serif;background:linear-gradient(160deg,#6F263D,#0A1220 70%);color:#fff;min-height:100vh}
main{max-width:560px;margin:auto;padding:24px 16px}
h1{margin:0 0 4px}.stripe{height:8px;background:linear-gradient(#fff 25%,#236192 25% 75%,#fff 75%);margin-bottom:20px}
label{display:block;margin:18px 0 6px;color:#A2AAAD;font-weight:600}
textarea,input{width:100%;box-sizing:border-box;padding:10px;border-radius:10px;border:2px solid #236192;background:#101A2B;color:#fff;font-size:15px}
textarea{height:130px}small{color:#A2AAAD}
button{margin-top:22px;width:100%;padding:14px;border:0;border-radius:12px;background:#236192;color:#fff;font-size:17px;font-weight:700}
.msg{background:#236192;padding:12px;border-radius:10px;margin-bottom:12px}
</style></head><body><main>
<h1>TV Dash</h1><div class="stripe"></div>
${if (msg != null) "<div class=\"msg\">${esc(msg)}</div>" else ""}
<form method="post" action="/save">
<label>Playlists (one link per line)</label>
<textarea name="playlists">${esc(Config.playlists.joinToString("\n"))}</textarea>
<small>Free, legal M3U playlists, e.g. from iptv-org.</small>
<label>TV guide link (optional)</label>
<input name="epg" value="${esc(Config.epg)}" placeholder="Blank = use the playlist's own guide">
<label>Favourite NHL team</label>
<input name="team" value="${esc(Config.team)}" maxlength="3" placeholder="COL">
<small>3-letter code. Gets the ticker spot and goal alerts.</small>
<label><input type="checkbox" name="horn" value="on" ${if (Config.horn) "checked" else ""} style="width:auto"> Goal horn sound</label>
<small>The red goal light always flashes; this turns the horn on or off.</small>
<small>Red edge glow over other apps (Netflix etc.): ${if (EdgeGlow.allowed) "On ✓" else "Off. Run the adb command in INSTALL.md, then open TV Dash."}</small>
<button>Save</button>
</form>
<form method="post" action="/test">
<button style="background:#6F263D">🚨 Test horn</button>
<small>Plays the goal banner, red light and horn on the TV, even if the horn is switched off.</small>
</form></main></body></html>"""
}
