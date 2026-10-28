package com.matt.tvdash

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.random.Random

private const val FAV = "★ Favourites"
private const val RECENT = "⟲ Recent"
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
    private lateinit var saver: FrameLayout
    private lateinit var saverBox: LinearLayout
    private lateinit var saverClock: TextView
    private lateinit var saverDate: TextView
    private lateinit var saverAvs: TextView
    private var lastScores: List<TickerItem>? = null
    private var favScore: Int? = null
    private var swallowUp = false
    private val ui = Handler(Looper.getMainLooper())
    private val idle = Runnable { showSaver() }
    private var allChannels: List<Channel> = emptyList()
    private var category = FAV

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = Prefs(this)
        fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rink()
            setPadding(dp(40), dp(24), dp(40), dp(16))
        }

        // Header
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(text("TV Dash", 30f, true), LinearLayout.LayoutParams(0, -2, 1f))
        clock = text("", 26f, false); header.addView(clock)
        root.addView(header)
        root.addView(stripe(), LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(8) })

        // Apps row
        root.addView(section("Apps  (long-press to pin)"))
        appsAdapter = TileAdapter(170, 110, onClick = { launchApp(it.key) }, onLong = { pin("apps", it.key); loadApps() })
        root.addView(hRow(appsAdapter), LinearLayout.LayoutParams(-1, dp(130)))

        // Categories
        root.addView(section("Free Live TV"))
        catAdapter = TileAdapter(160, 50, textOnly = true, onClick = { category = it.key; showChannels() })
        root.addView(hRow(catAdapter), LinearLayout.LayoutParams(-1, dp(70)))

        // Channels grid
        status = text("Loading channels…", 16f, false)
        root.addView(status)
        chAdapter = TileAdapter(160, 110, onClick = { play(it.key) }, onLong = { pin("ch", it.key); showChannels() })
        val grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, 6)
            adapter = chAdapter
        }
        root.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))

        // Sports ticker
        root.addView(stripe(), LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(8) })
        ticker = TickerView(this)
        root.addView(ticker, LinearLayout.LayoutParams(-1, dp(100)).apply { topMargin = dp(6) })

        // Avalanche goal alert
        goal = text("🚨  AVALANCHE GOAL!  🚨", 48f, true).apply {
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

        setContentView(FrameLayout(this).apply {
            addView(root, FrameLayout.LayoutParams(-1, -1))
            addView(saver, FrameLayout.LayoutParams(-1, -1))
            addView(goal, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        })
        tick()
        loadChannels()
        loadScores()
    }

    override fun onResume() {
        super.onResume()
        loadApps()
        if (allChannels.isNotEmpty()) showChannels()   // refresh Recent and guide titles
        resetIdle()
    }

    override fun onPause() { super.onPause(); ui.removeCallbacks(idle) }

    override fun onDestroy() { ui.removeCallbacksAndMessages(null); super.onDestroy() }

    // ---------- Sports ticker + Avalanche game mode ----------
    private fun loadScores() {
        thread {
            val items = Ticker.load()
            ui.post {
                if (isDestroyed) return@post
                val fav = items.filterIsInstance<TickerItem.Game>().firstOrNull { it.isFav }
                checkGoal(fav)
                saverAvs.text = favLine(fav)
                if (items != lastScores) { lastScores = items; ticker.setItems(items) }
                // Check more often while the Avs are playing so goal alerts arrive sooner
                ui.postDelayed({ loadScores() }, if (fav?.state == "live") 20_000 else 60_000)
            }
        }
    }

    private fun checkGoal(g: TickerItem.Game?) {
        val score = g?.favScore
        val before = favScore
        if (g?.state == "live" && score != null && before != null && score > before) showGoal()
        favScore = score
    }

    private fun showGoal() {
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
        val d = resources.displayMetrics.density
        saverBox.animate().translationX(Random.nextInt(-200, 200) * d).translationY(Random.nextInt(-120, 120) * d).setDuration(3_000).start()
    }

    // Any key wakes the dashboard; that key press is swallowed so it doesn't also click something
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        resetIdle()
        if (saver.visibility == View.VISIBLE && e.action == KeyEvent.ACTION_DOWN) {
            saver.visibility = View.GONE; swallowUp = true; return true
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
        appsAdapter.items = tiles
    }

    private fun launchApp(pkg: String) {
        val i = packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: packageManager.getLaunchIntentForPackage(pkg)
        if (i != null) startActivity(i) else toast("Can't open that app")
    }

    // ---------- Channels ----------
    private fun loadChannels() = thread {
        val list = try { M3U.load() } catch (e: Exception) {
            ui.post { status.text = "Couldn't load playlist: ${e.message}" }; return@thread
        }
        ui.post { allChannels = list; buildCategories(); showChannels() }

        // TV guide
        val epg = EPG_URL.ifBlank { M3U.epgUrl.orEmpty() }
        if (epg.isNotBlank()) try {
            Epg.load(epg, list.mapNotNull { it.tvgId }.toSet())
            ui.post { showChannels() }
        } catch (_: Exception) { }

        // Hide streams that don't answer
        val dead = StreamCheck.run(list)
        ui.post { Session.dead = dead; showChannels() }
    }

    private fun buildCategories() {
        val groups = allChannels.map { it.group }.distinct().sorted()
        catAdapter.items = (listOf(FAV, RECENT, "All") + groups).map { Tile(it, it) }
        if (prefs.get("ch").isEmpty()) category = "All"
    }

    private fun showChannels() {
        val favs = prefs.get("ch")
        val all = when (category) {
            FAV -> allChannels.filter { it.url in favs }
            RECENT -> prefs.recent().mapNotNull { u -> allChannels.firstOrNull { it.url == u } }
            "All" -> allChannels
            else -> allChannels.filter { it.group == category }
        }
        val list = all.filter { it.url !in Session.dead }
        val hidden = all.size - list.size
        Session.channels = list
        status.text = "$category · ${list.size} channels" +
            (if (hidden > 0) " · $hidden offline hidden" else "") + "  (long-press a channel to favourite)"
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

    // Add your own image as app/src/main/res/drawable/centre_ice.png to show it at centre ice
    private fun rink(): RinkBackground {
        val logoId = resources.getIdentifier("centre_ice", "drawable", packageName)
        return RinkBackground(if (logoId != 0) getDrawable(logoId)?.mutate() else null)
    }

    private fun stripe() = View(this).apply { background = JerseyStripe() }

    private fun hRow(a: TileAdapter) = RecyclerView(this).apply {
        layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
        adapter = a
    }

    private fun section(s: String) = text(s, 18f, true).apply {
        setTextColor(Theme.ice); setPadding(0, 24, 0, 4)
        setShadowLayer(6f, 0f, 2f, Theme.burgundy)
    }

    private fun text(s: String, size: Float, bold: Boolean) = TextView(this).apply {
        text = s; textSize = size; setTextColor(Color.WHITE)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
