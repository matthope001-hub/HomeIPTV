package com.matt.tvdash

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

// One team line on a ticker card
data class TeamLine(val abbr: String, val logo: String?, val record: String, val score: Int?)

// A ticker card: either a league label or a game
sealed class TickerItem {
    data class League(val name: String) : TickerItem()
    data class Game(val league: String, val state: String, val status: String, val away: TeamLine, val home: TeamLine) : TickerItem() {
        val isFav get() = league == "NHL" && (away.abbr == Config.team || home.abbr == Config.team)
        val favScore get() = if (home.abbr == Config.team) home.score else away.score
    }
}

// Free public ESPN scoreboard feeds (no key needed)
object Ticker {
    private val leagues = listOf(
        "NHL" to "hockey/nhl",
        "NBA" to "basketball/nba",
        "NFL" to "football/nfl",
        "MLB" to "baseball/mlb"
    )

    private val isoIn = SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private val timeOut = SimpleDateFormat("h:mm a", Locale.US)

    fun load(): List<TickerItem> {
        val out = mutableListOf<TickerItem>()
        for ((name, path) in leagues) {
            try {
                val json = JSONObject(httpGet("https://site.api.espn.com/apis/site/v2/sports/$path/scoreboard"))
                val events = json.optJSONArray("events") ?: continue
                if (events.length() == 0) continue
                out += TickerItem.League(name)
                for (i in 0 until events.length()) {
                    val ev = events.getJSONObject(i)
                    val type = ev.getJSONObject("status").getJSONObject("type")
                    val state = when (type.optString("state")) { "in" -> "live"; "post" -> "final"; else -> "pre" }
                    val status = when (state) {
                        "final" -> type.optString("shortDetail").uppercase().replace(" ", "")   // FINAL, FINAL/OT
                        "live" -> "● " + type.optString("shortDetail").uppercase()             // ● 2ND 12:34
                        else -> try { timeOut.format(isoIn.parse(ev.optString("date"))!!) } catch (_: Exception) { "" }
                    }
                    val teams = ev.getJSONArray("competitions").getJSONObject(0).getJSONArray("competitors")
                    var home: TeamLine? = null; var away: TeamLine? = null
                    for (j in 0 until teams.length()) {
                        val t = teams.getJSONObject(j)
                        val team = t.getJSONObject("team")
                        val rec = t.optJSONArray("records")?.optJSONObject(0)?.optString("summary") ?: ""
                        val line = TeamLine(
                            team.optString("abbreviation"),
                            team.optString("logo").ifBlank { null },
                            rec,
                            if (state == "pre") null else t.optString("score").toIntOrNull() ?: 0
                        )
                        if (t.optString("homeAway") == "home") home = line else away = line
                    }
                    if (home != null && away != null) out += TickerItem.Game(name, state, status, away, home)
                }
            } catch (_: Exception) { }
        }
        // Avalanche game goes to the front
        out.filterIsInstance<TickerItem.Game>().firstOrNull { it.isFav }?.let { g ->
            out.remove(g); out.add(0, TickerItem.League("AVS")); out.add(1, g)
        }
        return out
    }
}
