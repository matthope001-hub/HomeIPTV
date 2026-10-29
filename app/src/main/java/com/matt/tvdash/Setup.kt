package com.matt.tvdash

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayInputStream
import java.io.File
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
    var onUpdate: (() -> Unit)? = null   // set while TV Dash is on screen
    var onPhoto: (() -> Unit)? = null   // the centre-ice picture changed
    private var app: Context? = null
    private var server: ServerSocket? = null
    private val ui = Handler(Looper.getMainLooper())

    fun start(ctx: Context, prefs: Prefs) {
        app = ctx.applicationContext
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

    /** Loads the setup page from inside the TV; null if it works, otherwise what went wrong */
    fun selfTest(): String? = try {
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 5_000
            s.getOutputStream().write("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray())
            val status = s.getInputStream().bufferedReader().readLine().orEmpty()
            if (status.contains(" 200 ")) null else "answered: ${status.ifEmpty { "nothing" }}"
        }
    } catch (e: Exception) { e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "") }

    fun stop() { server?.close(); server = null }

    private fun handle(c: Socket, prefs: Prefs) {
        c.soTimeout = 10_000
        val input = c.getInputStream().buffered()
        val request = readLine(input).split(" ")
        val method = request.getOrElse(0) { "" }
        val path = request.getOrElse(1) { "/" }
        var len = 0
        var type = ""
        while (true) {
            val h = readLine(input)
            if (h.isEmpty()) break
            if (h.lowercase().startsWith("content-length:")) len = h.substringAfter(":").trim().toIntOrNull() ?: 0
            if (h.lowercase().startsWith("content-type:")) type = h.substringAfter(":").trim()
        }
        // Up to 25 MB, so a full-size phone photo fits
        val raw = if (len in 1..25_000_000) {
            val buf = ByteArray(len); var n = 0
            while (n < len) { val r = input.read(buf, n, len - n); if (r < 0) break; n += r }
            buf.copyOf(n)
        } else ByteArray(0)
        val body = if (raw.size <= 65_536) String(raw, Charsets.UTF_8) else ""

        // If building the page fails, send the error text instead of hanging up, so it shows on the phone
        val (status, html) = try {
            "200 OK" to when {
                method == "POST" && path == "/save" -> { save(body, prefs); page("Saved ✓ The TV is reloading channels.") }
                method == "POST" && path == "/test" -> page(testHorn())
                method == "POST" && path == "/update" -> page(updateTv())
                method == "POST" && path == "/photo" -> page(savePhoto(raw, type))
                method == "POST" && path == "/photo-remove" -> { photoFile()?.delete(); ui.post { onPhoto?.invoke() }; page("Picture removed.") }
                path == "/api/settings" -> settingsJson()   // read by the TV Dash Remote phone app
                path == "/favicon.ico" -> ""
                else -> page(null)
            }
        } catch (e: Throwable) {
            "500 Internal Server Error" to "<pre>TV Dash setup page error:\n${esc(e.stackTraceToString())}</pre>"
        }
        val bytes = html.toByteArray()
        c.getOutputStream().apply {
            write("HTTP/1.1 $status\r\nContent-Type: ${if (path.startsWith("/api/")) "application/json" else "text/html; charset=utf-8"}\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
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
        prefs.saveSettings(playlists, epg, team, form["horn"] == "on", form["english"] == "on")
        form["theme"]?.takeIf { it in Theme.presets }?.let { prefs.put("theme", it) }
        form["layout"]?.takeIf { it in Look.layouts }?.let {
            prefs.put("layout", it)
            prefs.put("gamenight", if (form["gamenight"] == "on") "on" else "off")   // sent together with the layout
        }
        Config.load(prefs)
        ui.post { onSaved?.invoke() }
    }

    // Runs a full goal alert (banner, red light, horn) on whatever TV Dash screen is showing
    private fun testHorn(): String {
        if (GoalWatch.listener == null) return "Open TV Dash on the TV first, then press Test horn again."
        ui.post { GoalFx.forceHorn = true; GoalWatch.listener?.invoke("Test from your phone") }
        return "Playing the goal horn on the TV."
    }

    // Current settings for the phone app
    private fun settingsJson(): String = org.json.JSONObject()
        .put("playlists", org.json.JSONArray(Config.playlists))
        .put("epg", Config.epg)
        .put("team", Config.team)
        .put("horn", Config.horn)
        .put("english", Config.english)
        .put("theme", Config.theme)
        .put("layout", Config.layout)
        .put("layouts", org.json.JSONArray(Look.layouts))
        .put("gameNight", Config.gameNight)
        .put("themes", org.json.JSONArray(Theme.presets.keys.toList()))
        .put("version", app?.let { it.packageManager.getPackageInfo(it.packageName, 0).versionName }.orEmpty())
        .toString()

    // Checks GitHub and, if there's a newer build, starts the update on the TV
    private fun updateTv(): String {
        val ctx = app ?: return "The TV isn't ready yet. Try again."
        val latest = Updater.latest() ?: return "Couldn't check GitHub for updates."
        val current = Updater.current(ctx)
        if (latest <= current) return "TV Dash is up to date (v1.0.$current)."
        val go = onUpdate ?: return "Open TV Dash on the TV, then try again."
        ui.post { go() }
        return "Downloading v1.0.$latest on the TV. Choose Install on the TV to finish."
    }

    /** Where the centre-ice picture is kept on the TV (never uploaded anywhere) */
    fun photoFile(): File? = app?.let { File(it.filesDir, "centre_ice.jpg") }

    // Takes the photo out of the form upload, turns it upright, crops the middle square and shrinks it
    private fun savePhoto(raw: ByteArray, type: String): String {
        val boundary = type.substringAfter("boundary=", "").trim('"')
        val text = String(raw, Charsets.ISO_8859_1)   // one char per byte, so positions match the bytes
        val name = text.indexOf("filename=")
        if (boundary.isEmpty() || name < 0) return "No picture received. Choose a photo first."
        val start = text.indexOf("\r\n\r\n", name).takeIf { it >= 0 }?.plus(4) ?: return "Couldn't read the upload."
        val end = text.indexOf("\r\n--$boundary", start).takeIf { it >= 0 } ?: return "Couldn't read the upload."
        val bytes = raw.copyOfRange(start, end)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return "That file isn't a picture the TV can read. Try a JPG or PNG."
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 800) sample *= 2
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return "That file isn't a picture the TV can read."
        val turn = when (ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val side = minOf(bmp.width, bmp.height)
        bmp = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side,
            Matrix().apply { postRotate(turn) }, true)
        val f = photoFile() ?: return "The TV isn't ready yet. Try again."
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        ui.post { onPhoto?.invoke() }
        return "Picture saved ✓ It's at centre ice now."
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun page(msg: String?) = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>TV Dash setup</title>
<style>
body{margin:0;font-family:system-ui,sans-serif;background:linear-gradient(160deg,#6F263D,#0A1220 70%);color:#fff;min-height:100vh}
main{max-width:560px;margin:auto;padding:24px 16px}
h1{margin:0 0 4px}.stripe{height:8px;background:linear-gradient(#fff 25%,#236192 25% 75%,#fff 75%);margin-bottom:20px}
label{display:block;margin:18px 0 6px;color:#A2AAAD;font-weight:600}
textarea,input,select{width:100%;box-sizing:border-box;padding:10px;border-radius:10px;border:2px solid #236192;background:#101A2B;color:#fff;font-size:15px}
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
<label><input type="checkbox" name="english" value="on" ${if (Config.english) "checked" else ""} style="width:auto"> English channels only</label>
<small>Offline channels are always hidden.</small>
<label>TV guide link (optional)</label>
<input name="epg" value="${esc(Config.epg)}" placeholder="Blank = use the playlist's own guide">
<label>Favourite NHL team</label>
<input name="team" value="${esc(Config.team)}" maxlength="3" placeholder="COL">
<small>3-letter code. Gets the ticker spot and goal alerts.</small>
<label>Colours</label>
<label>Layout</label>
<select name="layout">${Look.layouts.joinToString("") { k -> "<option value=\"$k\"${if (k == Config.layout) " selected" else ""}>${Look.label(k)}</option>" }}</select>
<label><input type="checkbox" name="gamenight" value="on" ${if (Config.gameNight) "checked" else ""} style="width:auto"> Switch to Jumbotron while ${esc(Config.team)} is playing</label>
<label>Colours</label>
<select name="theme">${Theme.presets.keys.joinToString("") { k -> "<option value=\"$k\"${if (k == Config.theme) " selected" else ""}>${Theme.label(k)}</option>" }}</select>
<label><input type="checkbox" name="horn" value="on" ${if (Config.horn) "checked" else ""} style="width:auto"> Goal horn sound</label>
<small>The red goal light always flashes; this turns the horn on or off.</small>
<small>Red edge glow over other apps (Netflix etc.): ${if (EdgeGlow.allowed) "On ✓" else "Off. Run the adb command in INSTALL.md, then open TV Dash."}</small>
<button>Save</button>
</form>
<form method="post" action="/photo" enctype="multipart/form-data">
<label>Centre-ice picture</label>
<input type="file" name="photo" accept="image/*">
<button>Upload picture</button>
<small>Shown faintly at centre ice. It stays on the TV and isn't uploaded anywhere else.</small>
</form>
<form method="post" action="/photo-remove"><button style="background:#333">Remove picture</button></form>
<form method="post" action="/update"><button>⬆ Update TV Dash</button></form>
<form method="post" action="/test">
<button style="background:#6F263D">🚨 Test horn</button>
<small>Plays the goal banner, red light and horn on the TV, even if the horn is switched off.</small>
</form></main></body></html>"""
}
