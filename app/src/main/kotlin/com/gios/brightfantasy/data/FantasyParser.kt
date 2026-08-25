package com.gios.brightfantasy.data

import com.gios.brightfantasy.model.DraftPick
import com.gios.brightfantasy.model.FantasyTeam
import com.gios.brightfantasy.model.FreeAgent
import com.gios.brightfantasy.model.LeagueInfo
import com.gios.brightfantasy.model.LeagueMove
import com.gios.brightfantasy.model.Matchup
import com.gios.brightfantasy.model.MatchupSide
import com.gios.brightfantasy.model.Roster
import com.gios.brightfantasy.model.RosterSlot
import com.gios.brightfantasy.model.SlotCount
import org.json.JSONArray
import org.json.JSONObject

/**
 * ESPN's JSON to this app's models.
 *
 * No Android imports — `org.json` is in the JDK on Android and available as a plain jar
 * off it — so every function here is compiled and asserted against in the sandbox before
 * it reaches CI. That matters more than usual: this is a reverse-engineered API with no
 * contract, and a field that quietly changes shape produces a screen of zeroes rather
 * than a crash.
 */
object FantasyParser {

    // ------------------------------------------------------------------ league

    fun parseLeague(json: String, leagueId: String): LeagueInfo? = runCatching {
        val root = JSONObject(json)
        val settings = root.optJSONObject("settings") ?: JSONObject()
        val status = root.optJSONObject("status") ?: JSONObject()
        val schedule = settings.optJSONObject("scheduleSettings") ?: JSONObject()
        val roster = settings.optJSONObject("rosterSettings") ?: JSONObject()
        val scoring = settings.optJSONObject("scoringSettings") ?: JSONObject()

        LeagueInfo(
            id = leagueId,
            season = root.optInt("seasonId", 0),
            name = settings.optString("name", "League"),
            size = settings.optInt("size", 0),
            // Before a season starts this is 0, and a week-0 request answers with an
            // empty scoreboard rather than an error, so the caller floors it at 1.
            scoringPeriodId = root.optInt("scoringPeriodId", 0),
            matchupPeriodId = status.optInt("currentMatchupPeriod", 1),
            firstScoringPeriod = status.optInt("firstScoringPeriod", 1),
            finalScoringPeriod = status.optInt("finalScoringPeriod", 17),
            regularSeasonWeeks = schedule.optInt("matchupPeriodCount", 14),
            playoffTeams = schedule.optInt("playoffTeamCount", 6),
            isActive = status.optBoolean("isActive", true),
            lineupSlots = parseLineupSlots(roster.optJSONObject("lineupSlotCounts")),
            scoringName = scoringTypeName(scoring),
        )
    }.getOrNull()

    /**
     * `lineupSlotCounts` is an object keyed by slot id as a string, with a count of 0 for
     * every slot the league does not use — so it lists all 25 slots whatever the format.
     * Dropping the zeroes is what turns it into the league's actual lineup.
     */
    private fun parseLineupSlots(counts: JSONObject?): List<SlotCount> {
        if (counts == null) return emptyList()
        val out = mutableListOf<SlotCount>()
        for (key in counts.keys()) {
            val id = key.toIntOrNull() ?: continue
            val n = counts.optInt(key, 0)
            if (n > 0) out += SlotCount(id, n)
        }
        return out.sortedBy { Espn.slotRank(it.slotId) }
    }

    /**
     * There is no field that says "PPR". The reception rule is stat id 53 with a points
     * value, buried in a 200-entry scoring-rule array, so the label is read off that.
     */
    private fun scoringTypeName(scoring: JSONObject): String {
        val items = scoring.optJSONArray("scoringItems") ?: return "Custom"
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            if (item.optInt("statId", -1) != 53) continue
            val points = item.optDouble("points", 0.0)
            return when {
                points >= 1.0 -> "PPR"
                points > 0.0 -> "Half PPR"
                else -> "Standard"
            }
        }
        return "Standard"
    }

    // ------------------------------------------------------------------ teams

    fun parseTeams(json: String): List<FantasyTeam> = runCatching {
        val teams = JSONObject(json).optJSONArray("teams") ?: return emptyList()
        (0 until teams.length()).mapNotNull { i ->
            teams.optJSONObject(i)?.let { parseTeam(it) }
        }
    }.getOrDefault(emptyList())

    private fun parseTeam(t: JSONObject): FantasyTeam {
        val overall = t.optJSONObject("record")?.optJSONObject("overall") ?: JSONObject()
        val counter = t.optJSONObject("transactionCounter") ?: JSONObject()
        // ESPN dropped the separate `location`/`nickname` pair for a single `name` in
        // 2023, but a league that has not been touched since can still answer with the
        // old pair and an empty `name`.
        val name = t.optString("name").ifBlank {
            listOf(t.optString("location"), t.optString("nickname"))
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .ifBlank { "Team ${t.optInt("id")}" }
        }
        return FantasyTeam(
            id = t.optInt("id"),
            name = name,
            abbrev = t.optString("abbrev", "").ifBlank { name.take(4).uppercase() },
            logoUrl = t.optString("logo").takeIf { it.isNotBlank() },
            owners = t.optJSONArray("owners")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it) }
            } ?: emptyList(),
            wins = overall.optInt("wins"),
            losses = overall.optInt("losses"),
            ties = overall.optInt("ties"),
            pointsFor = overall.optDouble("pointsFor", 0.0),
            pointsAgainst = overall.optDouble("pointsAgainst", 0.0),
            streakLength = overall.optInt("streakLength"),
            streakType = overall.optString("streakType", ""),
            playoffSeed = t.optInt("playoffSeed"),
            waiverRank = t.optInt("waiverRank"),
            divisionId = t.optInt("divisionId"),
            acquisitions = counter.optInt("acquisitions"),
            trades = counter.optInt("trades"),
            moveToActive = counter.optInt("moveToActive"),
        )
    }

    // ------------------------------------------------------------------ schedule

    /**
     * The schedule: who plays whom, and for how many points.
     *
     * Scores only — no rosters, deliberately. `mMatchupScore` with a `scoringPeriodId`
     * *does* inline a roster for that week, and it is a trap: the player objects inside it
     * are stripped down to a stats array with no name, no position and no pro team, so a
     * screen built on it renders a column of blank rows. Rosters come from `mRoster` with
     * `forTeamId`, which is both complete and cheaper for the two teams that matter.
     *
     * @param week when non-null, only matchups for that period.
     */
    fun parseMatchups(
        json: String,
        week: Int? = null,
    ): List<Matchup> = runCatching {
        val schedule = JSONObject(json).optJSONArray("schedule") ?: return emptyList()
        (0 until schedule.length()).mapNotNull { i ->
            val m = schedule.optJSONObject(i) ?: return@mapNotNull null
            val period = m.optInt("matchupPeriodId")
            if (week != null && period != week) return@mapNotNull null
            val home = m.optJSONObject("home") ?: return@mapNotNull null
            val away = m.optJSONObject("away")
            Matchup(
                id = m.optInt("id"),
                matchupPeriodId = period,
                home = parseSide(home, period),
                // A bye in the fantasy playoffs is a matchup with no `away` at all, not
                // an away side with a null team id.
                away = away?.let { parseSide(it, period) },
                winner = m.optString("winner", "UNDECIDED"),
                playoffTier = m.optString("playoffTierType", "NONE"),
            )
        }
    }.getOrDefault(emptyList())

    private fun parseSide(side: JSONObject, period: Int): MatchupSide {
        val teamId = side.optInt("teamId")
        return MatchupSide(
            teamId = teamId,
            // `totalPoints` is the season-to-date figure in this view and the week's in
            // others. `pointsByScoringPeriod` is unambiguous, so prefer it and fall back.
            // It is also available before any roster has been fetched, which is what lets
            // the scoreboard render off one 52 KB request.
            points = side.optJSONObject("pointsByScoringPeriod")
                ?.optDouble(period.toString())
                ?.takeIf { !it.isNaN() }
                ?: side.optDouble("totalPoints", 0.0),
            roster = null,
        )
    }

    // ------------------------------------------------------------------ rosters

    fun parseRoster(
        json: String,
        teamId: Int,
        week: Int,
        proSchedule: ProSchedule? = null,
    ): Roster? = runCatching {
        val teams = JSONObject(json).optJSONArray("teams") ?: return null
        for (i in 0 until teams.length()) {
            val t = teams.optJSONObject(i) ?: continue
            if (t.optInt("id") != teamId) continue
            val roster = t.optJSONObject("roster") ?: continue
            return parseRosterEntries(roster, teamId, week, proSchedule)
        }
        null
    }.getOrNull()

    private fun parseRosterEntries(
        roster: JSONObject,
        teamId: Int,
        week: Int,
        pro: ProSchedule?,
    ): Roster {
        val entries = roster.optJSONArray("entries") ?: JSONArray()
        val slots = (0 until entries.length()).mapNotNull { i ->
            entries.optJSONObject(i)?.let { parseSlot(it, week, pro) }
        }
        return Roster(teamId = teamId, week = week, slots = slots)
    }

    private fun parseSlot(entry: JSONObject, week: Int, pro: ProSchedule?): RosterSlot? {
        val pool = entry.optJSONObject("playerPoolEntry") ?: return null
        val player = pool.optJSONObject("player") ?: return null
        val stats = player.optJSONArray("stats") ?: JSONArray()
        val proTeamId = player.optInt("proTeamId")
        val game = pro?.game(proTeamId, week)

        val weekActual = statEntry(stats, week, Espn.SOURCE_ACTUAL, Espn.SPLIT_WEEK)
        val weekProjected = statEntry(stats, week, Espn.SOURCE_PROJECTED, Espn.SPLIT_WEEK)
        val seasonTotal = statEntry(stats, 0, Espn.SOURCE_ACTUAL, Espn.SPLIT_SEASON)
        val seasonAverage = statEntry(stats, 0, Espn.SOURCE_ACTUAL, Espn.SPLIT_AVERAGE)

        return RosterSlot(
            playerId = player.optInt("id"),
            name = player.optString("fullName").ifBlank {
                listOf(player.optString("firstName"), player.optString("lastName"))
                    .filter { it.isNotBlank() }.joinToString(" ")
            },
            slotId = entry.optInt("lineupSlotId", Espn.SLOT_BENCH),
            positionId = player.optInt("defaultPositionId"),
            proTeamId = proTeamId,
            eligibleSlots = intList(player.optJSONArray("eligibleSlots")),
            // `appliedStatTotal` on the pool entry is the week's score when the payload
            // came from a week-scoped request, and the season's when it did not — so the
            // stats array is the only source that says which it is.
            points = weekActual?.optDouble("appliedTotal", 0.0) ?: 0.0,
            projected = weekProjected?.optDouble("appliedTotal", 0.0) ?: 0.0,
            seasonTotal = seasonTotal?.optDouble("appliedTotal", 0.0) ?: 0.0,
            seasonAverage = seasonAverage?.optDouble("appliedTotal", 0.0) ?: 0.0,
            // Two fields say the same thing and disagree: the entry's is the roster's view
            // (what the manager sees) and the player's is the league-wide one. The entry
            // wins where it exists.
            injuryStatus = entry.optString("injuryStatus").ifBlank {
                player.optString("injuryStatus", "")
            },
            percentOwned = player.optJSONObject("ownership")?.optDouble("percentOwned", 0.0) ?: 0.0,
            positionalRank = pool.optJSONObject("ratings")
                ?.optJSONObject("0")?.optInt("positionalRanking", 0) ?: 0,
            lineupLocked = pool.optBoolean("lineupLocked", false),
            statLine = statLine(weekActual),
            kickoffMillis = game?.kickoffMillis ?: 0L,
            opponentProTeamId = game?.opponentOf(proTeamId) ?: 0,
            // On a bye there is no game, and a player with no game has nothing still to
            // come — treating "no game" as "not started yet" is what makes a bye week
            // read as four starters who have not played.
            gameFinished = game?.finished ?: true,
        )
    }

    /**
     * The one stat entry that matches all three axes.
     *
     * They all live in one flat array with no ordering guarantee, so indexing it — which
     * is what most examples do — gets a projection instead of a result about half the
     * time, and silently.
     */
    private fun statEntry(stats: JSONArray, period: Int, source: Int, split: Int): JSONObject? {
        for (i in 0 until stats.length()) {
            val s = stats.optJSONObject(i) ?: continue
            if (s.optInt("scoringPeriodId", -1) != period) continue
            if (s.optInt("statSourceId", -1) != source) continue
            if (s.optInt("statSplitTypeId", -1) != split) continue
            return s
        }
        return null
    }

    /** The week's box score, in reading order, with the zeroes dropped. */
    private fun statLine(entry: JSONObject?): List<Pair<String, Double>> {
        val raw = entry?.optJSONObject("stats") ?: return emptyList()
        return Espn.STAT_LABELS.mapNotNull { (id, label) ->
            val value = raw.optDouble(id.toString(), 0.0)
            if (value == 0.0 || value.isNaN()) null else label to value
        }
    }

    private fun intList(arr: JSONArray?): List<Int> =
        if (arr == null) emptyList() else (0 until arr.length()).map { arr.optInt(it) }

    // ------------------------------------------------------------------ free agents

    fun parseFreeAgents(json: String, season: Int, week: Int): List<FreeAgent> = runCatching {
        val players = JSONObject(json).optJSONArray("players") ?: return emptyList()
        (0 until players.length()).mapNotNull { i ->
            val e = players.optJSONObject(i) ?: return@mapNotNull null
            val p = e.optJSONObject("player") ?: return@mapNotNull null
            val stats = p.optJSONArray("stats") ?: JSONArray()
            val ownership = p.optJSONObject("ownership") ?: JSONObject()
            FreeAgent(
                playerId = p.optInt("id"),
                name = p.optString("fullName"),
                positionId = p.optInt("defaultPositionId"),
                proTeamId = p.optInt("proTeamId"),
                eligibleSlots = intList(p.optJSONArray("eligibleSlots")),
                status = e.optString("status", "FREEAGENT"),
                percentOwned = ownership.optDouble("percentOwned", 0.0),
                percentStarted = ownership.optDouble("percentStarted", 0.0),
                // The number that actually decides a waiver claim: who is being added
                // right now, not who is already owned.
                percentChange = ownership.optDouble("percentChange", 0.0),
                projected = statEntry(stats, week, Espn.SOURCE_PROJECTED, Espn.SPLIT_WEEK)
                    ?.optDouble("appliedTotal", 0.0) ?: 0.0,
                lastWeek = statEntry(stats, week - 1, Espn.SOURCE_ACTUAL, Espn.SPLIT_WEEK)
                    ?.optDouble("appliedTotal", 0.0) ?: 0.0,
                seasonTotal = statEntry(stats, 0, Espn.SOURCE_ACTUAL, Espn.SPLIT_SEASON)
                    ?.optDouble("appliedTotal", 0.0) ?: 0.0,
                seasonAverage = statEntry(stats, 0, Espn.SOURCE_ACTUAL, Espn.SPLIT_AVERAGE)
                    ?.optDouble("appliedTotal", 0.0) ?: 0.0,
                injuryStatus = p.optString("injuryStatus", ""),
                positionalRank = e.optJSONObject("ratings")
                    ?.optJSONObject("0")?.optInt("positionalRanking", 0) ?: 0,
            )
        }
    }.getOrDefault(emptyList())

    // ------------------------------------------------------------------ activity

    /**
     * The league activity feed.
     *
     * Each topic is one moment in time holding several messages, and the message type id
     * is the only thing that says what happened. 244 (a trade) is shaped differently from
     * the rest — `from`/`to` are team ids there and `from` is a bid amount on a waiver
     * claim, which is the trap.
     */
    fun parseActivity(json: String, playerName: (Int) -> String?): List<LeagueMove> =
        runCatching {
            val topics = JSONObject(json).optJSONArray("topics") ?: return emptyList()
            (0 until topics.length()).mapNotNull { i ->
                val topic = topics.optJSONObject(i) ?: return@mapNotNull null
                val messages = topic.optJSONArray("messages") ?: return@mapNotNull null
                val added = mutableListOf<String>()
                val dropped = mutableListOf<String>()
                var teamId = 0
                var type = "ROSTER"
                var bid = 0
                for (j in 0 until messages.length()) {
                    val m = messages.optJSONObject(j) ?: continue
                    val target = m.optInt("targetId")
                    val who = playerName(target) ?: "#$target"
                    when (m.optInt("messageTypeId")) {
                        178 -> { added += who; type = "FREEAGENT"; teamId = m.optInt("to", teamId) }
                        180 -> {
                            added += who
                            type = "WAIVER"
                            teamId = m.optInt("to", teamId)
                            // On a waiver claim `from` is the winning bid, not a team.
                            bid = m.optInt("from", 0)
                        }
                        179, 181 -> { dropped += who; teamId = m.optInt("to", teamId) }
                        239 -> { dropped += who; teamId = m.optInt("for", teamId) }
                        244 -> { added += who; type = "TRADE_ACCEPT"; teamId = m.optInt("to", teamId) }
                        else -> Unit
                    }
                }
                if (added.isEmpty() && dropped.isEmpty()) return@mapNotNull null
                LeagueMove(
                    id = topic.optString("id", "$i"),
                    type = type,
                    teamId = teamId,
                    date = topic.optLong("date"),
                    added = added,
                    dropped = dropped,
                    bid = bid,
                )
            }
        }.getOrDefault(emptyList())

    // ------------------------------------------------------------------ draft

    fun parseDraft(json: String): List<DraftPick> = runCatching {
        val picks = JSONObject(json).optJSONObject("draftDetail")?.optJSONArray("picks")
            ?: return emptyList()
        (0 until picks.length()).mapNotNull { i ->
            val p = picks.optJSONObject(i) ?: return@mapNotNull null
            DraftPick(
                overall = p.optInt("overallPickNumber"),
                round = p.optInt("roundId"),
                roundPick = p.optInt("roundPickNumber"),
                teamId = p.optInt("teamId"),
                playerId = p.optInt("playerId"),
                bidAmount = p.optInt("bidAmount"),
                keeper = p.optBoolean("keeper", false),
            )
        }.sortedBy { it.overall }
    }.getOrDefault(emptyList())

    // ------------------------------------------------------------------ pro schedule

    /**
     * Every NFL game of the season, keyed by pro team and week.
     *
     * This is a separate season-level endpoint, not part of the league payload, and it is
     * the only source for three things the roster screen needs: kickoff time, opponent,
     * and whether the game is over. The fantasy roster carries points but nothing that
     * distinguishes "scored nothing" from "has not played" — and on a Sunday afternoon
     * that is the entire question the screen exists to answer.
     */
    class ProSchedule(
        private val games: Map<Int, Map<Int, ProGame>>,
        val byeWeeks: Map<Int, Int>,
    ) {
        fun game(proTeamId: Int, week: Int): ProGame? = games[proTeamId]?.get(week)

        fun isBye(proTeamId: Int, week: Int): Boolean = byeWeeks[proTeamId] == week

        companion object {
            val EMPTY = ProSchedule(emptyMap(), emptyMap())
        }
    }

    data class ProGame(
        val id: Long,
        val kickoffMillis: Long,
        val homeProTeamId: Int,
        val awayProTeamId: Int,
        /**
         * ESPN's `statsOfficial`, which flips when the box score is finalised rather than
         * when the clock hits zero — a few minutes later, which is the behaviour wanted
         * here anyway since a stat correction can still move points.
         */
        val finished: Boolean,
        val startTimeTbd: Boolean,
    ) {
        fun opponentOf(proTeamId: Int): Int =
            if (proTeamId == homeProTeamId) awayProTeamId else homeProTeamId

        fun isHome(proTeamId: Int): Boolean = proTeamId == homeProTeamId
    }

    fun parseProSchedule(json: String): ProSchedule = runCatching {
        val teams = JSONObject(json).optJSONObject("settings")?.optJSONArray("proTeams")
            ?: return ProSchedule.EMPTY
        val byTeam = mutableMapOf<Int, MutableMap<Int, ProGame>>()
        val byes = mutableMapOf<Int, Int>()
        for (i in 0 until teams.length()) {
            val t = teams.optJSONObject(i) ?: continue
            val id = t.optInt("id")
            byes[id] = t.optInt("byeWeek", 0)
            val periods = t.optJSONObject("proGamesByScoringPeriod") ?: continue
            val weeks = mutableMapOf<Int, ProGame>()
            for (key in periods.keys()) {
                val week = key.toIntOrNull() ?: continue
                val arr = periods.optJSONArray(key) ?: continue
                val g = arr.optJSONObject(0) ?: continue
                weeks[week] = ProGame(
                    id = g.optLong("id"),
                    kickoffMillis = g.optLong("date"),
                    homeProTeamId = g.optInt("homeProTeamId"),
                    awayProTeamId = g.optInt("awayProTeamId"),
                    finished = g.optBoolean("statsOfficial", false),
                    startTimeTbd = g.optBoolean("startTimeTBD", false),
                )
            }
            byTeam[id] = weeks
        }
        ProSchedule(byTeam, byes)
    }.getOrDefault(ProSchedule.EMPTY)
}
