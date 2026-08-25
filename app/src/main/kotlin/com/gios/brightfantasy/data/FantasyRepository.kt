package com.gios.brightfantasy.data

import com.gios.brightfantasy.model.DraftPick
import com.gios.brightfantasy.model.FreeAgent
import com.gios.brightfantasy.model.LeagueMove
import com.gios.brightfantasy.model.LeagueState
import com.gios.brightfantasy.model.Roster
import com.gios.brightfantasy.model.RosterSlot
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Every request the app makes, and the rules about which one to make.
 *
 * The rules matter more here than usual. ESPN's league payload is one endpoint with a
 * `view` parameter, and asking for the obvious combination of views is the difference
 * between 50 KB and 1.1 MB — on a phone that is frequently on a subway platform. The
 * sizes below are measured against a twelve-team league, not estimated:
 *
 * | request                                   | bytes |
 * |-------------------------------------------|-------|
 * | `mTeam` + `mSettings` + `mStandings`      |  61 K |
 * | `mMatchupScore`, no week                  |  52 K |
 * | `mMatchupScore` + week (rosters inline)   | 353 K |
 * | `mRoster`, whole league                   | 1.1 M |
 * | `mRoster` + `forTeamId`                   |  94 K |
 * | `mBoxscore`                               | 926 K |
 *
 * So: the season view never asks for a week, the week view asks for one team where it
 * can, and `mBoxscore` is never used at all — `mMatchupScore` with a week carries the
 * same rosters for a third of the bytes.
 */
class FantasyRepository(
    private val api: FantasyApi,
    private val prefs: Prefs,
    private val cacheDir: File,
) {

    sealed interface Outcome<out T> {
        data class Ok<T>(val value: T) : Outcome<T>
        data class Failed(val message: String, val needsLogin: Boolean = false) : Outcome<Nothing>
    }

    private fun <T> FantasyApi.Result.map(parse: (String) -> T?): Outcome<T> = when (this) {
        is FantasyApi.Result.Ok ->
            parse(body)?.let { Outcome.Ok(it) }
                ?: Outcome.Failed("ESPN answered with something this app could not read.")
        is FantasyApi.Result.Denied ->
            Outcome.Failed(message, needsLogin = code == 401 || code == 403)
        is FantasyApi.Result.Offline ->
            Outcome.Failed("No connection.")
    }

    // ------------------------------------------------------------------ the league

    /**
     * The season as a whole: settings, every team, every matchup, no rosters.
     *
     * Two calls rather than one. Asking for the schedule and the teams together works and
     * returns a payload with both, but ESPN also composes views — some combinations answer
     * with a *different* set of fields than either view alone — and keeping them apart
     * means a change to one cannot quietly empty the other.
     */
    fun loadLeague(): Outcome<LeagueState> {
        val season = prefs.effectiveSeason()
        val leagueId = prefs.leagueId
        if (leagueId.isBlank()) return Outcome.Failed("No league set.", needsLogin = true)

        val metaUrl = Espn.readUrl(season, leagueId, listOf("mSettings", "mTeam", "mStandings"))
        val meta = api.get(metaUrl)
        val metaBody = when (meta) {
            is FantasyApi.Result.Ok -> meta.body
            is FantasyApi.Result.Denied ->
                return Outcome.Failed(meta.message, meta.code == 401 || meta.code == 403)
            is FantasyApi.Result.Offline ->
                return cachedLeague()?.let { Outcome.Ok(it) } ?: Outcome.Failed("No connection.")
        }

        val info = FantasyParser.parseLeague(metaBody, leagueId)
            ?: return Outcome.Failed("ESPN answered with something this app could not read.")
        val teams = FantasyParser.parseTeams(metaBody)

        val scheduleUrl = Espn.readUrl(season, leagueId, listOf("mMatchupScore"))
        val matchups = when (val schedule = api.get(scheduleUrl)) {
            is FantasyApi.Result.Ok -> FantasyParser.parseMatchups(schedule.body)
            // A league whose schedule has not been generated yet is a real state in
            // August, not an error; the rest of the screen is still worth showing.
            else -> emptyList()
        }

        val state = LeagueState(info, teams, matchups, System.currentTimeMillis())
        cacheLeague(metaBody)
        return Outcome.Ok(state)
    }

    /**
     * The scoreboard for one week: every matchup and its two scores, no rosters.
     *
     * Deliberately fetched without a `scoringPeriodId`. Adding one makes ESPN inline a
     * roster for every team — 353 KB instead of 52 KB — and the inlined players have no
     * names on them, so the extra 300 KB buys nothing this app can draw.
     */
    fun loadWeek(week: Int): Outcome<List<com.gios.brightfantasy.model.Matchup>> {
        val season = prefs.effectiveSeason()
        val url = Espn.readUrl(season, prefs.leagueId, listOf("mMatchupScore"))
        return api.get(url).map { FantasyParser.parseMatchups(it, week) }
    }

    /**
     * One head-to-head with both lineups on it.
     *
     * Three requests rather than one, and cheaper than the one: the scoreboard (52 KB) to
     * find the opponent, then each side's roster by `forTeamId` (94 KB each). The
     * whole-league roster view is 1.1 MB for the same information about ten teams nobody
     * is looking at.
     *
     * The scores on screen come from the scoreboard, never from adding the rosters up —
     * see [com.gios.brightfantasy.model.Roster.complete].
     */
    fun loadMatchup(teamId: Int, week: Int): Outcome<MatchupDetail> {
        val matchups = when (val result = loadWeek(week)) {
            is Outcome.Ok -> result.value
            is Outcome.Failed -> return result
        }
        val matchup = matchups.firstOrNull { it.involves(teamId) }
            ?: return Outcome.Failed("No matchup for week $week.")
        val mine = (loadRoster(teamId, week) as? Outcome.Ok)?.value
        val theirs = matchup.opponentOf(teamId)
            ?.let { (loadRoster(it.teamId, week) as? Outcome.Ok)?.value }
        return Outcome.Ok(MatchupDetail(matchup, teamId, mine, theirs))
    }

    data class MatchupDetail(
        val matchup: com.gios.brightfantasy.model.Matchup,
        val teamId: Int,
        val myRoster: Roster?,
        val theirRoster: Roster?,
    ) {
        val mine get() = matchup.sideFor(teamId)
        val theirs get() = matchup.opponentOf(teamId)
    }

    /** One team's roster for one week, at a fraction of the whole-league payload. */
    fun loadRoster(teamId: Int, week: Int): Outcome<Roster> {
        val season = prefs.effectiveSeason()
        val pro = proSchedule(season)
        val url = Espn.readUrl(
            season,
            prefs.leagueId,
            listOf("mRoster"),
            scoringPeriodId = week.coerceAtLeast(1),
            forTeamId = teamId,
        )
        return api.get(url).map { FantasyParser.parseRoster(it, teamId, week, pro) }
    }

    // ------------------------------------------------------------------ the wire

    fun freeAgents(
        week: Int,
        slotIds: List<Int> = Espn.SEARCHABLE_SLOTS,
        onWaivers: Boolean? = null,
        sort: Espn.PlayerSort = Espn.PlayerSort.OWNERSHIP,
        limit: Int = 50,
        offset: Int = 0,
    ): Outcome<List<FreeAgent>> {
        val season = prefs.effectiveSeason()
        val url = Espn.readUrl(
            season,
            prefs.leagueId,
            listOf("kona_player_info"),
            scoringPeriodId = week.coerceAtLeast(1),
        )
        val filter = Espn.playerFilter(
            slotIds = slotIds,
            season = season,
            scoringPeriodId = week.coerceAtLeast(1),
            limit = limit,
            offset = offset,
            onWaivers = onWaivers,
            sort = sort,
        )
        return api.get(url, filter).map { FantasyParser.parseFreeAgents(it, season, week) }
    }

    // ------------------------------------------------------------------ activity

    /**
     * The league's add/drop/trade feed.
     *
     * A 404 here is normal rather than broken: leagues created before ESPN introduced
     * message topics have no communication group at all, and so does a brand new league
     * in which nothing has happened yet. Both should read as "nothing to show".
     */
    fun activity(limit: Int = 25): Outcome<List<LeagueMove>> {
        val season = prefs.effectiveSeason()
        val url = Espn.READ_HOST + Espn.leaguePath(season, prefs.leagueId) +
            "/communication/?view=kona_league_communication"
        val filter = "{\"topics\":{" +
            "\"filterType\":{\"value\":[\"ACTIVITY_TRANSACTIONS\"]}," +
            "\"limit\":$limit,\"limitPerMessageSet\":{\"value\":25},\"offset\":0," +
            "\"sortMessageDate\":{\"sortPriority\":1,\"sortAsc\":false}," +
            "\"sortFor\":{\"sortPriority\":2,\"sortAsc\":false}," +
            "\"filterIncludeMessageTypeIds\":{\"value\":[178,180,179,239,181,244]}" +
            "}}"
        return when (val result = api.get(url, filter)) {
            is FantasyApi.Result.Ok -> {
                val ids = playerIdsInActivity(result.body)
                val names = playerNames(ids)
                Outcome.Ok(FantasyParser.parseActivity(result.body) { names[it] })
            }
            is FantasyApi.Result.Denied ->
                if (result.code == 404) Outcome.Ok(emptyList())
                else Outcome.Failed(result.message, result.code == 401 || result.code == 403)
            is FantasyApi.Result.Offline -> Outcome.Failed("No connection.")
        }
    }

    fun draft(): Outcome<List<DraftPick>> {
        val season = prefs.effectiveSeason()
        val url = Espn.readUrl(season, prefs.leagueId, listOf("mDraftDetail"))
        return api.get(url).map { FantasyParser.parseDraft(it) }
    }

    // ------------------------------------------------------------------ player names

    /**
     * Player id to name, for the two feeds that carry ids and nothing else.
     *
     * The activity feed and the draft board both reference players purely by id, and the
     * only cheap way back to a name is to ask for those ids specifically. Asking for the
     * player universe instead is several megabytes for a screen that needs forty names.
     *
     * Cached to disk for the season, because a player's name does not change and the
     * draft board asks for two hundred of them at once.
     */
    fun playerNames(ids: Collection<Int>): Map<Int, String> {
        if (ids.isEmpty()) return emptyMap()
        val season = prefs.effectiveSeason()
        val file = File(cacheDir, "names-$season.json")
        val known = readNameCache(file)
        val missing = ids.filter { it > 0 && it !in known }
        if (missing.isEmpty()) return known

        val resolved = known.toMutableMap()
        // ESPN takes the whole list in one filter, but a five-hundred-id request is a
        // payload nobody wants on LTE, so it goes out in chunks.
        for (chunk in missing.distinct().chunked(50)) {
            val url = Espn.readUrl(season, prefs.leagueId, listOf("kona_playercard"))
            val filter = "{\"players\":{\"filterIds\":{\"value\":[${chunk.joinToString(",")}]}}}"
            val body = (api.get(url, filter) as? FantasyApi.Result.Ok)?.body ?: continue
            runCatching {
                val players = JSONObject(body).optJSONArray("players") ?: JSONArray()
                for (i in 0 until players.length()) {
                    val p = players.optJSONObject(i)?.optJSONObject("player") ?: continue
                    val name = p.optString("fullName")
                    if (name.isNotBlank()) resolved[p.optInt("id")] = name
                }
            }
        }
        writeNameCache(file, resolved)
        return resolved
    }

    /**
     * One player's whole season, week by week.
     *
     * The plain player card answers with season totals only. The weekly log is gated
     * behind `filterStatsForTopScoringPeriodIds`, whose `additionalValue` is a list of
     * composite stat ids — source and season concatenated — rather than anything
     * self-describing. Without it the game log is silently empty, which reads as a player
     * who has not played.
     */
    fun playerLog(playerId: Int, weeks: Int = 18): Outcome<List<Pair<Int, Double>>> {
        val season = prefs.effectiveSeason()
        val url = Espn.readUrl(season, prefs.leagueId, listOf("kona_playercard"))
        val filter = "{\"players\":{" +
            "\"filterIds\":{\"value\":[$playerId]}," +
            "\"filterStatsForTopScoringPeriodIds\":{\"value\":$weeks," +
            "\"additionalValue\":[\"00$season\",\"10$season\",\"reg\",\"postseason\"]}" +
            "}}"
        return api.get(url, filter).map { body ->
            runCatching {
                val p = JSONObject(body).optJSONArray("players")
                    ?.optJSONObject(0)?.optJSONObject("player") ?: return@runCatching null
                val stats = p.optJSONArray("stats") ?: return@runCatching emptyList()
                (0 until stats.length())
                    .mapNotNull { stats.optJSONObject(it) }
                    .filter {
                        it.optInt("statSplitTypeId") == Espn.SPLIT_WEEK &&
                            it.optInt("statSourceId") == Espn.SOURCE_ACTUAL
                    }
                    .map { it.optInt("scoringPeriodId") to it.optDouble("appliedTotal", 0.0) }
                    .sortedBy { it.first }
            }.getOrNull()
        }
    }

    // ------------------------------------------------------------------ writes

    /**
     * Move players between slots.
     *
     * A swap is two items in one transaction, not two transactions — sending them
     * separately means the first one lands on a full lineup and is refused.
     *
     * **Not verified against a real account.** The session that wrote this had read
     * access to a public league and no ESPN login, so the payload below is the shape the
     * ESPN web client sends and not something observed working. Every failure surfaces
     * ESPN's own message, so the first real attempt will say what is wrong rather than
     * "could not save".
     */
    fun setLineup(teamId: Int, week: Int, moves: List<Move>): Outcome<Unit> {
        if (moves.isEmpty()) return Outcome.Ok(Unit)
        val season = prefs.effectiveSeason()
        val items = moves.joinToString(",") { move ->
            "{\"playerId\":${move.playerId},\"type\":\"LINEUP\"," +
                "\"fromLineupSlotId\":${move.fromSlotId}," +
                "\"toLineupSlotId\":${move.toSlotId}}"
        }
        val body = "{" +
            "\"isLeagueManager\":false," +
            "\"teamId\":$teamId," +
            "\"type\":\"${Espn.TYPE_LINEUP}\"," +
            "\"memberId\":\"${prefs.swid}\"," +
            "\"scoringPeriodId\":$week," +
            "\"executionType\":\"EXECUTE\"," +
            "\"items\":[$items]" +
            "}"
        return api.post(Espn.writeUrl(season, prefs.leagueId), body).map { Unit }
    }

    /**
     * Add a player, dropping one to make room.
     *
     * The drop rides in the same transaction as the add for the same reason a lineup swap
     * does: a roster at its size limit refuses the add on its own, and having dropped
     * somebody first and then failed to add is the worst of the three outcomes.
     */
    fun addDrop(
        teamId: Int,
        week: Int,
        addPlayerId: Int,
        dropPlayerId: Int?,
        onWaivers: Boolean,
        bid: Int = 0,
    ): Outcome<Unit> {
        val season = prefs.effectiveSeason()
        val items = buildList {
            add(
                "{\"playerId\":$addPlayerId,\"type\":\"ADD\",\"toTeamId\":$teamId," +
                    "\"fromTeamId\":0,\"toLineupSlotId\":${Espn.SLOT_BENCH}}",
            )
            if (dropPlayerId != null) {
                add(
                    "{\"playerId\":$dropPlayerId,\"type\":\"DROP\",\"fromTeamId\":$teamId," +
                        "\"toTeamId\":0,\"fromLineupSlotId\":${Espn.SLOT_BENCH}}",
                )
            }
        }.joinToString(",")
        val type = if (onWaivers) Espn.TYPE_WAIVER else Espn.TYPE_FREEAGENT
        val body = "{" +
            "\"isLeagueManager\":false," +
            "\"teamId\":$teamId," +
            "\"type\":\"$type\"," +
            "\"memberId\":\"${prefs.swid}\"," +
            "\"scoringPeriodId\":$week," +
            "\"executionType\":\"EXECUTE\"," +
            (if (onWaivers) "\"bidAmount\":$bid," else "") +
            "\"items\":[$items]" +
            "}"
        return api.post(Espn.writeUrl(season, prefs.leagueId), body).map { Unit }
    }

    data class Move(val playerId: Int, val fromSlotId: Int, val toSlotId: Int)

    /**
     * The two moves a swap is, derived from the two players being swapped.
     *
     * Kept here rather than in the screen so the rule is in one place: each player takes
     * the other's slot, and the caller has already checked that both are eligible.
     */
    fun swapMoves(a: RosterSlot, b: RosterSlot): List<Move> = listOf(
        Move(a.playerId, a.slotId, b.slotId),
        Move(b.playerId, b.slotId, a.slotId),
    )

    // ------------------------------------------------------------------ pro schedule

    /**
     * The NFL calendar, cached for a week. It changes only when a game is flexed, and a
     * kickoff time that is a few hours stale is worth far more than a fetch that fails.
     */
    private fun proSchedule(season: Int): FantasyParser.ProSchedule {
        cachedPro?.let { (cachedSeason, schedule) ->
            if (cachedSeason == season) return schedule
        }
        val file = File(cacheDir, "pro-$season.json")
        val fresh = file.exists() &&
            System.currentTimeMillis() - file.lastModified() < PRO_CACHE_MILLIS
        val body = if (fresh) {
            runCatching { file.readText() }.getOrNull()
        } else {
            val fetched = (
                api.get(
                    "${Espn.READ_HOST}/apis/v3/games/${Espn.GAME}/seasons/$season" +
                        "?view=proTeamSchedules_wl",
                ) as? FantasyApi.Result.Ok
                )?.body
            if (fetched != null) runCatching { file.writeText(fetched) }
            fetched ?: runCatching { file.readText() }.getOrNull()
        }
        val parsed = body?.let { FantasyParser.parseProSchedule(it) }
            ?: FantasyParser.ProSchedule.EMPTY
        cachedPro = season to parsed
        return parsed
    }

    @Volatile
    private var cachedPro: Pair<Int, FantasyParser.ProSchedule>? = null

    // ------------------------------------------------------------------ disk

    private fun cacheLeague(body: String) {
        runCatching { File(cacheDir, "league.json").writeText(body) }
    }

    /**
     * The last league payload that arrived, so opening the app underground shows last
     * night's standings rather than an error.
     */
    private fun cachedLeague(): LeagueState? {
        val file = File(cacheDir, "league.json")
        val body = runCatching { file.readText() }.getOrNull() ?: return null
        val info = FantasyParser.parseLeague(body, prefs.leagueId) ?: return null
        return LeagueState(info, FantasyParser.parseTeams(body), emptyList(), file.lastModified())
    }

    private fun readNameCache(file: File): Map<Int, String> = runCatching {
        val obj = JSONObject(file.readText())
        buildMap {
            for (key in obj.keys()) {
                val id = key.toIntOrNull() ?: continue
                put(id, obj.optString(key))
            }
        }
    }.getOrDefault(emptyMap())

    private fun writeNameCache(file: File, names: Map<Int, String>) {
        runCatching {
            val obj = JSONObject()
            names.forEach { (id, name) -> obj.put(id.toString(), name) }
            file.writeText(obj.toString())
        }
    }

    private fun playerIdsInActivity(body: String): Set<Int> = runCatching {
        val topics = JSONObject(body).optJSONArray("topics") ?: return emptySet()
        buildSet {
            for (i in 0 until topics.length()) {
                val messages = topics.optJSONObject(i)?.optJSONArray("messages") ?: continue
                for (j in 0 until messages.length()) {
                    messages.optJSONObject(j)?.optInt("targetId")?.takeIf { it > 0 }?.let { add(it) }
                }
            }
        }
    }.getOrDefault(emptySet())

    private companion object {
        const val PRO_CACHE_MILLIS = 7L * 24 * 60 * 60 * 1000
    }
}
