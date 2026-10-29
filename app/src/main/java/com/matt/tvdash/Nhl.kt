package com.matt.tvdash

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import kotlin.concurrent.thread

// Your team's NHL game from the public NHL feed (no key needed): live or not, and its goals so far
data class NhlGame(val id: Long, val live: Boolean, val favGoals: List<String>)

// One of your team's goals for the Goals row; clip is the NHL highlight link once it's posted
data class GoalInfo(val key: String, val scorer: String, val detail: String, val clip: String?, val headshot: String?, val clipId: Long? = null)

object Nhl {
    fun favGame(): NhlGame? = try {
        val games = JSONObject(httpGet("https://api-web.nhle.com/v1/score/now")).optJSONArray("games")
        val team = Config.team
        games?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
            ?.firstOrNull { abbr(it.optJSONObject("awayTeam")) == team || abbr(it.optJSONObject("homeTeam")) == team }
            ?.let { g ->
                val goals = g.optJSONArray("goals")
                val fav = (0 until (goals?.length() ?: 0)).map { goals!!.getJSONObject(it) }
                    .filter { teamOf(it) == team }
                    .map { describe(it) }
                NhlGame(g.optLong("id"), g.optString("gameState") in setOf("LIVE", "CRIT"), fav)
            }
    } catch (_: Exception) { null }

    /** Your team's goals in a game, with highlight clip links when the NHL has posted them */
    fun goals(gameId: Long): List<GoalInfo> = try {
        val scoring = JSONObject(httpGet("https://api-web.nhle.com/v1/gamecenter/$gameId/landing"))
            .optJSONObject("summary")?.optJSONArray("scoring")
        val out = mutableListOf<GoalInfo>()
        for (p in 0 until (scoring?.length() ?: 0)) {
            val per = scoring!!.getJSONObject(p)
            val num = per.optJSONObject("periodDescriptor")?.optInt("number") ?: (p + 1)
            val goals = per.optJSONArray("goals") ?: continue
            for (i in 0 until goals.length()) {
                val g = goals.getJSONObject(i)
                if (teamOf(g) != Config.team) continue
                val time = g.optString("timeInPeriod")
                out += GoalInfo("$num-$time", name(g), "P$num $time",
                    g.optString("highlightClipSharingUrl").ifBlank { null }, g.optString("headshot").ifBlank { null },
                    g.optLong("highlightClip").takeIf { it > 0 })
            }
        }
        out
    } catch (_: Exception) { emptyList() }

    private fun abbr(t: JSONObject?) = t?.optString("abbrev").orEmpty()

    // teamAbbrev comes as "COL" or {"default":"COL"} depending on the feed
    private fun teamOf(goal: JSONObject) = goal.optJSONObject("teamAbbrev")?.optString("default") ?: goal.optString("teamAbbrev")

    private fun name(o: JSONObject): String = o.optJSONObject("name")?.optString("default")
        ?: listOf("firstName", "lastName").joinToString(" ") { o.optJSONObject(it)?.optString("default").orEmpty() }.trim()

    // "Nathan MacKinnon (Makar, Rantanen) · P2 12:34"
    private fun describe(goal: JSONObject): String {
        val assists = goal.optJSONArray("assists")
        val names = (0 until (assists?.length() ?: 0)).map { name(assists!!.getJSONObject(it)).substringAfterLast(" ") }
        val period = goal.optJSONObject("periodDescriptor")?.optInt("number") ?: goal.optInt("period")
        return buildString {
            append(name(goal))
            if (names.isNotEmpty()) append(" (${names.joinToString(", ")})")
            if (period > 0) append("  ·  P$period ${goal.optString("timeInPeriod")}")
        }
    }
}

fun goalTitle() = if (Config.team == "COL") "AVALANCHE GOAL!" else "${Config.team} GOAL!"

/** Checks the NHL feed every 10 seconds during a live game and calls [listener] with the scorer */
object GoalWatch {
    var listener: ((String) -> Unit)? = null      // the TV Dash screen that's showing, if any
    var onGoalsChanged: (() -> Unit)? = null
    @Volatile var goals: List<GoalInfo> = emptyList()
    private var app: Context? = null
    private val ui = Handler(Looper.getMainLooper())
    private var gameId = 0L
    private var seen = 0          // our goals already alerted in this game
    private var running = false

    fun start(ctx: Context) { app = ctx.applicationContext; if (!running) { running = true; poll() } }
    fun stop() { running = false; ui.removeCallbacksAndMessages(null) }

    private fun poll() {
        thread {
            val g = Nhl.favGame()
            val list = g?.let { Nhl.goals(it.id) }
            ui.post {
                if (!running) return@post
                if (g != null) {
                    // First look at a game (e.g. app opened mid-game): remember its goals without alerting
                    if (g.id != gameId) gameId = g.id
                    else if (g.live && g.favGoals.size > seen) {
                        // TV Dash on screen: full alert. Another app on screen: silent red edge glow
                        val l = listener
                        if (l != null) l(g.favGoals.last()) else app?.let { EdgeGlow.show(it) }
                    }
                    seen = g.favGoals.size   // also drops back if a goal is overturned
                    if (list != null && list != goals) { goals = list; onGoalsChanged?.invoke() }
                }
                ui.postDelayed({ poll() }, if (g?.live == true) 10_000 else 120_000)
            }
        }
    }
}
