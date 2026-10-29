package com.matt.tvdash.remote

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Phone remote for TV Dash: the same settings as the TV's setup page, sent straight to the TV
 * over your Wi-Fi (no browser involved).
 */
class MainActivity : AppCompatActivity() {

    private val burgundy = Color.parseColor("#6F263D")
    private val steel = Color.parseColor("#236192")
    private val night = Color.parseColor("#0A1220")
    private val silver = Color.parseColor("#A2AAAD")

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var address: EditText
    private lateinit var playlists: EditText
    private lateinit var epg: EditText
    private lateinit var team: EditText
    private lateinit var horn: CheckBox
    private lateinit var english: CheckBox
    private lateinit var themes: RadioGroup
    private lateinit var status: TextView

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { uploadPhoto(it) } }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val saved = getSharedPreferences("remote", MODE_PRIVATE).getString("tv", "").orEmpty()

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }
        col.addView(TextView(this).apply { text = "TV Dash Remote"; textSize = 26f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD })
        col.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.WHITE, steel, steel, Color.WHITE))
        }, LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(8); bottomMargin = dp(8) })
        status = TextView(this).apply { setTextColor(Color.WHITE); textSize = 15f; setPadding(0, dp(4), 0, dp(4)) }
        col.addView(status)

        address = field(col, "TV address", saved, "192.168.40.17:8080")
        row(col, button("Connect") { load() }, button("Find TV") { findTv() })

        playlists = field(col, "Playlists (one link per line)", "", "https://…/playlist.m3u", lines = 4)
        epg = field(col, "TV guide link (optional)", "", "Blank = use the playlist's own guide")
        team = field(col, "Favourite NHL team (3 letters)", "", "COL")
        horn = check(col, "Goal horn sound")
        english = check(col, "English channels only")
        label(col, "Colours")
        themes = RadioGroup(this).also { col.addView(it) }
        col.addView(button("Save to TV") { save() })

        label(col, "Centre-ice picture")
        row(col, button("Choose picture") { pickPhoto.launch("image/*") }, button("Remove", burgundy) { post("/photo-remove", "Removing…") })
        label(col, "TV Dash app")
        col.addView(button("⬆ Update TV Dash") { post("/update", "Checking for updates…") })
        label(col, "Goal horn")
        col.addView(button("🚨 Test horn", burgundy) { post("/test", "Sending…") })

        setContentView(ScrollView(this).apply { setBackgroundColor(night); addView(col) })
        if (saved.isNotEmpty()) load() else status.text = "Press Find TV, or type the address shown in TV Dash's header."
    }

    // ---------- Talking to the TV ----------
    private fun base() = "http://" + address.text.toString().trim().removePrefix("http://").trimEnd('/')

    private fun request(url: String, method: String = "GET", type: String? = null, body: ByteArray? = null, timeout: Int = 5_000): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = timeout; c.readTimeout = 30_000; c.requestMethod = method
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", type)
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code >= 400) throw IOException("the TV answered $code")
            return text
        } finally { c.disconnect() }
    }

    // Runs [work] off the main thread and shows the message it returns
    private fun job(busy: String, work: () -> String) {
        status.text = busy
        thread {
            val msg = try { work() } catch (e: Exception) { "Couldn't reach the TV (${e.message}). Is TV Dash open on the TV?" }
            ui.post { status.text = msg }
        }
    }

    // The TV's pages put their result in <div class="msg">…</div>
    private fun message(html: String) = Regex("<div class=\"msg\">(.*?)</div>").find(html)?.groupValues?.get(1)
        ?.replace("&amp;", "&") ?: "Done"

    private fun post(path: String, busy: String) = job(busy) {
        message(request(base() + path, "POST", "application/x-www-form-urlencoded", ByteArray(0)))
    }

    private fun load() = job("Connecting…") {
        val j = JSONObject(request(base() + "/api/settings"))
        val lists = j.getJSONArray("playlists").let { a -> (0 until a.length()).joinToString("\n") { a.getString(it) } }
        ui.post {
            playlists.setText(lists)
            epg.setText(j.optString("epg"))
            team.setText(j.optString("team"))
            horn.isChecked = j.optBoolean("horn", true)
            english.isChecked = j.optBoolean("english", true)
            themes.removeAllViews()
            val names = j.optJSONArray("themes")
            for (k in 0 until (names?.length() ?: 0)) {
                val n = names!!.getString(k)
                themes.addView(RadioButton(this).apply {
                    id = View.generateViewId(); tag = n
                    text = n.replaceFirstChar { it.uppercase() }; setTextColor(Color.WHITE); textSize = 15f
                })
                if (n == j.optString("theme")) themes.check(themes.getChildAt(k).id)
            }
            getSharedPreferences("remote", MODE_PRIVATE).edit().putString("tv", address.text.toString().trim()).apply()
        }
        "Connected to TV Dash v${j.optString("version")}"
    }

    private fun save() {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        val form = buildList {
            add("playlists=" + enc(playlists.text.toString()))
            add("epg=" + enc(epg.text.toString()))
            add("team=" + enc(team.text.toString()))
            if (horn.isChecked) add("horn=on")
            if (english.isChecked) add("english=on")
            themes.findViewById<RadioButton>(themes.checkedRadioButtonId)?.tag?.let { add("theme=" + enc(it.toString())) }
        }.joinToString("&").toByteArray()
        job("Saving…") { message(request(base() + "/save", "POST", "application/x-www-form-urlencoded", form)) }
    }

    private fun uploadPhoto(uri: Uri) = job("Sending picture…") {
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@job "Couldn't open that picture."
        val b = "----tvdash${System.currentTimeMillis()}"
        val head = "--$b\r\nContent-Disposition: form-data; name=\"photo\"; filename=\"photo.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray()
        val tail = "\r\n--$b--\r\n".toByteArray()
        message(request(base() + "/photo", "POST", "multipart/form-data; boundary=$b", head + bytes + tail))
    }

    // Tries every address on this Wi-Fi for TV Dash's setup port (8080-8082)
    private fun findTv() = job("Looking for the TV… (about 10 seconds)") {
        val me = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
            ?: return@job "Connect the phone to your home Wi-Fi first."
        val prefix = me.substringBeforeLast('.')
        val found = AtomicReference<String?>()
        val pool = Executors.newFixedThreadPool(48)
        for (port in 8080..8082) for (i in 1..254) pool.execute {
            if (found.get() == null) try {
                val at = "$prefix.$i:$port"
                if (request("http://$at/api/settings", timeout = 400).contains("\"playlists\"")) found.compareAndSet(null, at)
            } catch (_: Exception) { }
        }
        pool.shutdown(); pool.awaitTermination(60, TimeUnit.SECONDS)
        val at = found.get() ?: return@job "Couldn't find TV Dash. Make sure it's open on the TV, then try again."
        ui.post { address.setText(at); load() }
        "Found the TV at $at"
    }

    // ---------- Views ----------
    private fun label(col: LinearLayout, s: String) = col.addView(TextView(this).apply {
        text = s; textSize = 14f; setTextColor(silver); typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(16), 0, dp(4))
    })

    private fun field(col: LinearLayout, title: String, value: String, hint: String, lines: Int = 1): EditText {
        label(col, title)
        return EditText(this).apply {
            setText(value); this.hint = hint; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); textSize = 15f
            if (lines > 1) { minLines = lines; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_VARIATION_URI }
            else inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.parseColor("#101A2B")); setStroke(dp(2), steel) }
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }.also { col.addView(it) }
    }

    private fun check(col: LinearLayout, title: String) = CheckBox(this).apply {
        text = title; setTextColor(Color.WHITE); textSize = 15f; setPadding(0, dp(8), 0, dp(8))
    }.also { col.addView(it) }

    private fun button(title: String, color: Int = steel, click: () -> Unit) = Button(this).apply {
        text = title; isAllCaps = false; setTextColor(Color.WHITE); textSize = 16f
        background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(color) }
        setOnClickListener { click() }
    }

    private fun row(col: LinearLayout, vararg buttons: Button) = col.addView(LinearLayout(this).apply {
        buttons.forEachIndexed { i, b -> addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = dp(10) }) }
    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
}
