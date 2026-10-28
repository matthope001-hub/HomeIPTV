package com.matt.tvdash

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.random.Random

private const val FAV = "★ Favourites"
private const val RECENT = "⟲ Recent"
private const val SETUP = "📱 Phone setup"
private const val IDLE_MS = 5 * 60_000L   // screensaver after 5 minutes without a key press

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var appsAdapter: TileAdapter
    private lateinit var catAdapter: TileAdapter
    private lateinit var chAdapter: TileAdapter
    private lateinit var clock: TextView
    private lateinit var status: TextView
    private lateinit var ticker: TickerView
    private lateinit var goal: TextView
    private lateinit var light: View
    private lateinit var saver: FrameLayout
    private lateinit var saverBox: LinearLayout
    private lateinit var saverClock: TextView
    private lateinit var saverDate: TextView
    private lateinit var saverAvs: TextView
    private var lastScores: List<TickerItem>? = null
    private lateinit var setup: LinearLayout
    private lateinit var appsRow: RecyclerView
    private lateinit var catRow: RecyclerView
    private lateinit var grid: RecyclerView
    private var mover: Mover? = null

    // A tile being moved: its row, columns in that row, the order before the move, and how to save it
    private class Mover(val a: TileAdapter, val rv: RecyclerView, val cols: Int, val before: List<Tile>, val save: (List<String>) -> Unit)
    // Everything is sized in design units: 1/540 of the screen height, so the layout fills
    // a 1080p or 4K screen the same way and nothing ends up off screen
    private var unit = 1f
    private fun dp(v: Int) = (v * unit).toInt()
    private var gen = 0   // bumps on each channel reload so older loads are ignored
    private var swallowUp = false
    private val ui = Handler(Looper.getMainLooper())
    private val idle = Runnable { showSaver() }
    private var allChannels: List<Channel> = emptyList()
    private var category = FAV

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = Prefs(this)
        Config.load(prefs)
        val url = setupUrl()
        unit = resources.displayMetrics.heightPixels / 540f
        val usable = resources.displayMetrics.widthPixels - 2 * dp(48)   // inside the TV-safe margins

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rink()
            setPadding(dp(48), dp(20), dp(48), dp(12))
            clipChildren = false
        }

        // Header
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(text("TV Dash", 22f, true))
        header.addView(text("📱 Phone setup: $url", 12f, false).apply { setTextColor(Theme.silver) },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(16) })
        clock = text("", 20f, false); header.addView(clock)
        root.addView(header)
        root.addView(stripe(), LinearLayout.LayoutParams(-1, dp(4)).apply { topMargin = dp(6) })

        // Apps row
        root.addView(section("Apps  (long-press to pin)"))
        appsAdapter = TileAdapter(112, 84, unit, 11f, onClick = { launchApp(it.key) }, onLong = { t ->
            menu(t.title, listOf(
                (if (t.pinned) "Unpin" else "Pin") to { pin("apps", t.key); loadApps() },
                "Move" to { startMove(appsAdapter, appsRow, 1, t.key) { prefs.setOrder("apps", it) } }))
        })
        appsAdapter.slots = usable / dp(120)
        appsRow = hRow(appsAdapter)
        root.addView(appsRow, LinearLayout.LayoutParams(-1, dp(92)))

        // Categories
        root.addView(section("Free Live TV"))
        catAdapter = TileAdapter(112, 34, unit, 12f, textOnly = true, onClick = { if (it.key == SETUP) showSetup() else { category = it.key; chAdapter.firstPage(); showChannels() } },
            onLong = { t -> startMove(catAdapter, catRow, 1, t.key) { prefs.setOrder("cat", it) } })
        catAdapter.items = listOf(SETUP, FAV, RECENT, "All").ordered(prefs.order("cat")) { it }.map { Tile(it, it) }
        catAdapter.slots = usable / dp(120)
        catRow = hRow(catAdapter)
        root.addView(catRow, LinearLayout.LayoutParams(-1, dp(42)))

        // Channels grid
        status = text("Loading channels…", 12f, false).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        root.addView(status)
        chAdapter = TileAdapter((usable / unit / 6).toInt() - 8, 76, unit, 10f, onClick = { play(it.key) }, onLong = { t ->
            val fav = if (t.pinned) "Remove from favourites" else "Add to favourites"
            // Channels can only be reordered in Favourites; other lists follow the playlist
            menu(t.title.substringBefore("\n"), listOfNotNull(
                fav to { pin("ch", t.key); showChannels() },
                if (category == FAV) "Move" to { startMove(chAdapter, grid, 6, t.key) { prefs.setOrder("ch", it); showChannels() } } else null))
        })
        grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, 6)
            adapter = chAdapter
            overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false; clipToPadding = false
        }
        root.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        // As many rows of 6 as fit in the space left; the rest go on "More" pages
        grid.addOnLayoutChangeListener { v, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) v.post { chAdapter.slots = 6 * maxOf(1, (bottom - top) / dp(84)) }
        }

        // Sports ticker
        root.addView(stripe(), LinearLayout.LayoutParams(-1, dp(4)).apply { topMargin = dp(4) })
        ticker = TickerView(this, unit)
        root.addView(ticker, LinearLayout.LayoutParams(-1, dp(66)).apply { topMargin = dp(4) })

        // Avalanche goal alert
        goal = text("", 44f, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(48), dp(24), dp(48), dp(24))
            visibility = View.GONE
            background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(Theme.burgundy); setStroke(dp(6), Theme.steel) }
        }

        // Screensaver: big clock and the Avs game, drifting slowly to protect the screen
        saverClock = text("", 110f, true)
        saverDate = text("", 30f, false).apply { setTextColor(Theme.ice) }
        saverAvs = text("", 32f, true)
        saverBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(saverClock); addView(saverDate)
            addView(saverAvs, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(24) })
        }
        saver = FrameLayout(this).apply {
            background = rink()
            visibility = View.GONE
            addView(saverBox, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        }

        // Phone setup: QR code that opens the settings page on your phone
        setup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(32), dp(32), dp(32), dp(32))
            visibility = View.GONE
            background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(Theme.night); setStroke(dp(4), Theme.steel) }
            addView(text("Scan with your phone", 28f, true))
            addView(ImageView(this@MainActivity).apply { setImageBitmap(qr(url, dp(200))) },
                LinearLayout.LayoutParams(dp(200), dp(200)).apply { topMargin = dp(16); bottomMargin = dp(16) })
            addView(text(url, 22f, false).apply { setTextColor(Theme.ice) })
            addView(text("Phone must be on the same Wi-Fi · any key closes", 16f, false).apply { setTextColor(Theme.silver) })
        }

        setContentView(FrameLayout(this).apply {
            addView(root, FrameLayout.LayoutParams(-1, -1))
            addView(saver, FrameLayout.LayoutParams(-1, -1))
            addView(setup, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            light = View(this@MainActivity).apply { setBackgroundColor(Color.parseColor("#C8102E")); visibility = View.GONE }
            addView(light, FrameLayout.LayoutParams(-1, -1))
            addView(goal, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        })
        tick()
        loadChannels()
        loadScores()
        GoalWatch.start()
        SetupServer.onSaved = { reload() }
        SetupServer.start(prefs)
    }

    override fun onResume() {
        super.onResume()
        loadApps()
        if (allChannels.isNotEmpty()) showChannels()   // refresh Recent and guide titles
        resetIdle()
        GoalWatch.listener = { showGoal(it) }
    }

    override fun onPause() { super.onPause(); ui.removeCallbacks(idle); GoalWatch.listener = null }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        GoalWatch.stop(); SetupServer.stop(); SetupServer.onSaved = null
        super.onDestroy()
    }

    // ---------- Sports ticker + Avalanche game mode ----------
    private fun loadScores() {
        thread {
            val items = Ticker.load()
            ui.post {
                if (isDestroyed) return@post
                val fav = items.filterIsInstance<TickerItem.Game>().firstOrNull { it.isFav }
                saverAvs.text = favLine(fav)
                if (items != lastScores) { lastScores = items; ticker.setItems(items) }
                // Check more often while the Avs are playing so goal alerts arrive sooner
                ui.postDelayed({ loadScores() }, if (fav?.state == "live") 20_000 else 60_000)
            }
        }
    }

    // Goal alerts come from GoalWatch (NHL feed, every 10 s during a live game)
    private fun showGoal(scorer: String) {
        goal.text = "🚨  ${goalTitle()}  🚨\n$scorer"
        GoalFx.play(this, light)
        goal.visibility = View.VISIBLE
        goal.scaleX = 0.4f; goal.scaleY = 0.4f
        goal.animate().scaleX(1f).scaleY(1f).setDuration(300).start()
        ui.postDelayed({ goal.visibility = View.GONE }, 8_000)
    }

    private fun favLine(g: TickerItem.Game?) = when {
        g == null -> ""
        g.state == "pre" -> "${g.away.abbr} @ ${g.home.abbr}  ·  ${g.status}"
        else -> "${g.away.abbr} ${g.away.score}  –  ${g.home.score} ${g.home.abbr}  ·  ${g.status}"
    }

    // ---------- Screensaver ----------
    private fun resetIdle() { ui.removeCallbacks(idle); ui.postDelayed(idle, IDLE_MS) }

    private fun showSaver() {
        saver.alpha = 0f; saver.visibility = View.VISIBLE
        saver.animate().alpha(1f).setDuration(800).start()
        drift()
    }

    private fun drift() {
        saverBox.animate().translationX(Random.nextInt(-200, 200) * unit).translationY(Random.nextInt(-100, 100) * unit).setDuration(3_000).start()
    }

    // Any key wakes the dashboard; that key press is swallowed so it doesn't also click something
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        resetIdle()
        mover?.let { m ->
            if (e.action == KeyEvent.ACTION_DOWN) when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> step(m, -1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> step(m, 1)
                KeyEvent.KEYCODE_DPAD_UP -> if (m.cols > 1) step(m, -m.cols)
                KeyEvent.KEYCODE_DPAD_DOWN -> if (m.cols > 1) step(m, m.cols)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    val keys = m.a.items.map { it.key }
                    endMove(); m.save(keys); swallowUp = true; toast("Moved")
                }
                KeyEvent.KEYCODE_BACK -> { endMove(); m.a.items = m.before; swallowUp = true }
            }
            return true   // the remote only moves the tile until it's dropped
        }
        if ((saver.visibility == View.VISIBLE || setup.visibility == View.VISIBLE) && e.action == KeyEvent.ACTION_DOWN) {
            saver.visibility = View.GONE; setup.visibility = View.GONE; swallowUp = true; return true
        }
        if (swallowUp && e.action == KeyEvent.ACTION_UP) { swallowUp = false; return true }
        return super.dispatchKeyEvent(e)
    }

    // ---------- Apps ----------
    private fun loadApps() {
        val pm = packageManager
        val pinned = prefs.get("apps")
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val tiles = pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != packageName }
            .map {
                val pkg = it.activityInfo.packageName
                val icon = pm.getApplicationBanner(pkg) ?: it.loadIcon(pm)
                Tile(pkg, it.loadLabel(pm).toString(), icon = icon, pinned = pkg in pinned)
            }
            .distinctBy { it.key }
            .sortedWith(compareBy<Tile>({ !it.pinned }, { it.title.lowercase() }))
            .ordered(prefs.order("apps")) { it.key }
        appsAdapter.items = tiles
    }

    private fun launchApp(pkg: String) {
        val i = packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: packageManager.getLaunchIntentForPackage(pkg)
        if (i != null) startActivity(i) else toast("Can't open that app")
    }

    // ---------- Channels ----------
    private fun loadChannels() {
        val my = ++gen
        thread {
            val list = try { M3U.loadAll(Config.playlists) } catch (e: Exception) {
                ui.post { if (my == gen) status.text = "Couldn't load playlist: ${e.message}  ·  use $SETUP to change it" }
                return@thread
            }
            ui.post { if (my == gen) { allChannels = list; buildCategories(); showChannels() } }

            // TV guide
            val epg = Config.epg.ifBlank { M3U.epgUrl.orEmpty() }
            if (epg.isNotBlank()) try {
                Epg.load(epg, list.mapNotNull { it.tvgId }.toSet())
                ui.post { if (my == gen) showChannels() }
            } catch (_: Exception) { }

            // Hide streams that don't answer
            val dead = StreamCheck.run(list)
            ui.post { if (my == gen) { Session.dead = dead; showChannels() } }
        }
    }

    // Settings were saved from the phone
    private fun reload() {
        Session.dead = emptySet(); Epg.guide = emptyMap()
        status.text = "Settings saved · reloading channels…"
        loadChannels()
    }

    private fun buildCategories() {
        val groups = allChannels.map { it.group }.distinct().sorted()
        catAdapter.items = (listOf(SETUP, FAV, RECENT, "All") + groups).ordered(prefs.order("cat")) { it }.map { Tile(it, it) }
        if (prefs.get("ch").isEmpty()) category = "All"
    }

    private fun showChannels() {
        val favs = prefs.get("ch")
        val all = when (category) {
            FAV -> allChannels.filter { it.url in favs }.ordered(prefs.order("ch")) { it.url }
            RECENT -> prefs.recent().mapNotNull { u -> allChannels.firstOrNull { it.url == u } }
            "All" -> allChannels
            else -> allChannels.filter { it.group == category }
        }
        val list = all.filter { it.url !in Session.dead }
        val hidden = all.size - list.size
        Session.channels = list
        status.text = "$category · ${list.size} channels" +
            (if (hidden > 0) " · $hidden offline hidden" else "") + "  (long-press a channel for options)"
        chAdapter.items = list.map {
            val now = Epg.nowNext(it.tvgId).first
            Tile(it.url, if (now != null) "${it.name}\n${now.title}" else it.name, iconUrl = it.logo, pinned = it.url in favs)
        }
    }

    private fun play(url: String) {
        val idx = Session.channels.indexOfFirst { it.url == url }
        startActivity(Intent(this, PlayerActivity::class.java).putExtra("idx", maxOf(idx, 0)))
    }

    // ---------- Helpers ----------
    private fun pin(set: String, key: String) =
        toast(if (prefs.toggle(set, key)) "Pinned" else "Unpinned")

    private fun tick() {
        val now = Date()
        clock.text = SimpleDateFormat("EEE MMM d  ·  h:mm a", Locale.getDefault()).format(now)
        saverClock.text = SimpleDateFormat("h:mm", Locale.getDefault()).format(now)
        saverDate.text = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(now)
        if (saver.visibility == View.VISIBLE) drift()
        ui.postDelayed({ tick() }, 30_000)
    }

    // ---------- Move tiles ----------
    private fun startMove(a: TileAdapter, rv: RecyclerView, cols: Int, key: String, save: (List<String>) -> Unit) {
        mover = Mover(a, rv, cols, a.items, save)
        a.moving = key
        toast(if (cols > 1) "Arrows to move · OK to drop · Back to cancel" else "◀ ▶ to move · OK to drop · Back to cancel")
    }

    private fun step(m: Mover, d: Int) {
        m.a.moveBy(d)
    }

    private fun endMove() { mover?.a?.moving = null; mover = null }

    private fun menu(title: String, actions: List<Pair<String, () -> Unit>>) {
        AlertDialog.Builder(this).setTitle(title)
            .setItems(actions.map { it.first }.toTypedArray()) { _, i -> actions[i].second() }
            .show()
    }

    private fun showSetup() { setup.visibility = View.VISIBLE }

    // Address of the setup page: the TV's own Wi-Fi/Ethernet address
    private fun setupUrl(): String {
        val ip = try {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
        } catch (_: Exception) { null }
        return "http://${ip ?: "<TV address>"}:${SetupServer.PORT}"
    }

    private fun qr(s: String, size: Int): Bitmap {
        val m = QRCodeWriter().encode(s, BarcodeFormat.QR_CODE, size, size)
        val px = IntArray(size * size) { if (m.get(it % size, it / size)) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }

    // Add your own image as app/src/main/res/drawable/centre_ice.png to show it at centre ice
    private fun rink(): RinkBackground {
        val logoId = resources.getIdentifier("centre_ice", "drawable", packageName)
        return RinkBackground(if (logoId != 0) getDrawable(logoId)?.mutate() else null)
    }

    private fun stripe() = View(this).apply { background = JerseyStripe() }

    private fun hRow(a: TileAdapter) = RecyclerView(this).apply {
        layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
        adapter = a
        overScrollMode = View.OVER_SCROLL_NEVER
        clipChildren = false; clipToPadding = false
    }

    private fun section(s: String) = text(s, 13f, true).apply {
        setTextColor(Theme.ice); setPadding(0, dp(6), 0, dp(2))
        setShadowLayer(6f, 0f, 2f, Theme.burgundy)
    }

    private fun text(s: String, size: Float, bold: Boolean) = TextView(this).apply {
        text = s; setTextSize(TypedValue.COMPLEX_UNIT_PX, size * unit); setTextColor(Color.WHITE)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
