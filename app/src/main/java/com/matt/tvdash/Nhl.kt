package com.matt.tvdash

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import kotlin.concurrent.thread

// Your team's NHL game from the public NHL feed (no key needed): live or not, and its goals so far
data class NhlGame(val id: Long, val live: Boolean, val favGoals: List<String>)

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
    var listener: ((String) -> Unit)? = null
    private val ui = Handler(Looper.getMainLooper())
    private var gameId = 0L
    private var seen = 0          // our goals already alerted in this game
    private var running = false

    fun start() { if (!running) { running = true; poll() } }
    fun stop() { running = false; ui.removeCallbacksAndMessages(null) }

    private fun poll() {
        thread {
            val g = Nhl.favGame()
            ui.post {
                if (!running) return@post
                if (g != null) {
                    // First look at a game (e.g. app opened mid-game): remember its goals without alerting
                    if (g.id != gameId) gameId = g.id
                    else if (g.live && g.favGoals.size > seen) listener?.invoke(g.favGoals.last())
                    seen = g.favGoals.size   // also drops back if a goal is overturned
                }
                ui.postDelayed({ poll() }, if (g?.live == true) 10_000 else 120_000)
            }
        }
    }
}
