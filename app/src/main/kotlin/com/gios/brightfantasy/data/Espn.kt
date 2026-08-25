package com.gios.brightfantasy.data

import java.time.LocalDate
import java.time.ZoneId

/**
 * Everything about ESPN's fantasy API that is a constant rather than a request.
 *
 * Pure Kotlin on purpose — no Android imports anywhere in this file — so the URL builders
 * and the slot arithmetic can be compiled and asserted against in the sandbox before any
 * of it reaches CI. See `EspnTest`.
 *
 * The API is undocumented and reverse-engineered. Everything below was verified live
 * against a public league (id 105491, seasons 2025 and 2026) rather than copied from a
 * blog post, and the notes say which parts are guesses.
 */
object Espn {

    /**
     * ESPN moved the read host in April 2024. `fantasy.espn.com` still answers for a
     * browser but returns 404 for these paths, which reads exactly like a wrong league id.
     */
    const val READ_HOST = "https://lm-api-reads.fantasy.espn.com"

    /**
     * Writes go to a different host. Reads are served from a cache tier that will happily
     * accept a POST and do nothing with it, so pointing a write at [READ_HOST] fails
     * silently rather than loudly.
     */
    const val WRITE_HOST = "https://lm-api-writes.fantasy.espn.com"

    const val GAME = "ffl"

    fun leaguePath(season: Int, leagueId: String): String =
        "/apis/v3/games/$GAME/seasons/$season/segments/0/leagues/$leagueId"

    fun readUrl(
        season: Int,
        leagueId: String,
        views: List<String>,
        scoringPeriodId: Int? = null,
        forTeamId: Int? = null,
    ): String = buildString {
        append(READ_HOST).append(leaguePath(season, leagueId))
        val params = mutableListOf<String>()
        views.forEach { params += "view=$it" }
        scoringPeriodId?.let { params += "scoringPeriodId=$it" }
        // Verified: this really does filter `mRoster` down to one team — 94 KB instead of
        // 1.1 MB for a twelve-team league. Worth reaching for on a phone that is often on
        // a subway platform.
        forTeamId?.let { params += "forTeamId=$it" }
        if (params.isNotEmpty()) append('?').append(params.joinToString("&"))
    }

    fun writeUrl(season: Int, leagueId: String): String =
        WRITE_HOST + leaguePath(season, leagueId) + "/transactions/"

    /** The player universe, which is not scoped to a league. Used by the news lookup. */
    fun playersUrl(season: Int): String =
        "$READ_HOST/apis/v3/games/$GAME/seasons/$season/players?scoringPeriodId=0&view=players_wl"

    // ------------------------------------------------------------------ season & week

    /**
     * Which fantasy season "now" belongs to.
     *
     * The NFL year rolls over in the spring, not on 1 January: a game played in January
     * 2026 belongs to season 2025. ESPN's own cutover is around the draft, so anything
     * from June onwards is the new season — before that the previous one is still the one
     * with data in it.
     */
    fun currentSeason(today: LocalDate): Int =
        if (today.monthValue >= 6) today.year else today.year - 1

    fun currentSeason(zone: ZoneId = ZoneId.systemDefault()): Int =
        currentSeason(LocalDate.now(zone))

    // ------------------------------------------------------------------ lineup slots

    /**
     * `lineupSlotId` — where a player is *slotted*, which is a different axis from what
     * position they play.
     *
     * The ids are not ordered by whether they start. 20 is the bench, 21 is IR and 24 is
     * the ESPN "engaged reserve" slot; **23 is FLEX and it starts**, so the obvious rule
     * — anything below the bench id is a starter — quietly files every FLEX player onto
     * the bench, leaves the starting lineup one player short, and drops that player out
     * of the optimal-lineup calculation too. It is a list of exceptions, not a threshold.
     */
    const val SLOT_BENCH = 20
    const val SLOT_IR = 21
    const val SLOT_ENGAGED_RESERVE = 24

    private val RESERVE_SLOTS = setOf(SLOT_BENCH, SLOT_IR, SLOT_ENGAGED_RESERVE)

    val SLOT_NAMES: Map<Int, String> = mapOf(
        0 to "QB",
        1 to "TQB",
        2 to "RB",
        3 to "RB/WR",
        4 to "WR",
        5 to "WR/TE",
        6 to "TE",
        7 to "OP",
        8 to "DT",
        9 to "DE",
        10 to "LB",
        11 to "DL",
        12 to "CB",
        13 to "S",
        14 to "DB",
        15 to "DP",
        16 to "D/ST",
        17 to "K",
        18 to "P",
        19 to "HC",
        20 to "BE",
        21 to "IR",
        23 to "FLEX",
        24 to "ER",
    )

    /**
     * The order a lineup is read in, which is not the numeric order of the slot ids.
     * Anything unlisted sorts after the named slots but before the bench.
     */
    private val SLOT_ORDER: List<Int> =
        listOf(0, 2, 4, 6, 23, 3, 5, 7, 16, 17, 18, 19, 11, 8, 9, 10, 12, 13, 14, 15, 20, 21, 24)

    fun slotRank(slotId: Int): Int =
        SLOT_ORDER.indexOf(slotId).let { if (it < 0) SLOT_ORDER.size - 3 else it }

    fun slotName(slotId: Int): String = SLOT_NAMES[slotId] ?: "?"

    fun isStarting(slotId: Int): Boolean = slotId !in RESERVE_SLOTS

    // ------------------------------------------------------------------ positions

    /** `defaultPositionId` — what the player actually is. Same numbers, different field. */
    val POSITION_NAMES: Map<Int, String> = mapOf(
        1 to "QB",
        2 to "RB",
        3 to "WR",
        4 to "TE",
        5 to "K",
        7 to "P",
        9 to "DT",
        10 to "DE",
        11 to "LB",
        12 to "CB",
        13 to "S",
        14 to "HC",
        16 to "D/ST",
    )

    fun positionName(positionId: Int): String = POSITION_NAMES[positionId] ?: "FLEX"

    // ------------------------------------------------------------------ pro teams

    /**
     * `proTeamId` to abbreviation. The gaps are real — 31 and 32 have never been used,
     * and Baltimore is 33 rather than 31 because the map predates the Ravens.
     */
    val PRO_TEAMS: Map<Int, String> = mapOf(
        0 to "FA",
        1 to "ATL", 2 to "BUF", 3 to "CHI", 4 to "CIN", 5 to "CLE", 6 to "DAL",
        7 to "DEN", 8 to "DET", 9 to "GB", 10 to "TEN", 11 to "IND", 12 to "KC",
        13 to "LV", 14 to "LAR", 15 to "MIA", 16 to "MIN", 17 to "NE", 18 to "NO",
        19 to "NYG", 20 to "NYJ", 21 to "PHI", 22 to "ARI", 23 to "PIT", 24 to "LAC",
        25 to "SF", 26 to "SEA", 27 to "TB", 28 to "WSH", 29 to "CAR", 30 to "JAX",
        33 to "BAL", 34 to "HOU",
    )

    fun proTeam(id: Int): String = PRO_TEAMS[id] ?: "—"

    // ------------------------------------------------------------------ stat sources

    /**
     * `statSourceId`: 0 is what happened, 1 is what ESPN projected. Both arrive in the
     * same `stats` array for the same scoring period, distinguished by nothing else, so
     * reading the first entry gets you a projection about half the time.
     */
    const val SOURCE_ACTUAL = 0
    const val SOURCE_PROJECTED = 1

    /**
     * `statSplitTypeId`: 0 is the season total, 1 is that single week, 2 is a per-game
     * average. A "season total" entry carries `scoringPeriodId = 0`.
     */
    const val SPLIT_SEASON = 0
    const val SPLIT_WEEK = 1
    const val SPLIT_AVERAGE = 2

    // ------------------------------------------------------------------ box score stats

    /**
     * The subset of ESPN's ~230 stat ids worth showing on a 3.9" screen, in the order a
     * line reads. The full map is enormous and most of it is scoring-rule scaffolding
     * (`every 5 passing yards`) rather than anything that happened in a game.
     */
    val STAT_LABELS: List<Pair<Int, String>> = listOf(
        3 to "PASS YD",
        4 to "PASS TD",
        20 to "INT",
        23 to "CAR",
        24 to "RUSH YD",
        25 to "RUSH TD",
        41 to "REC",
        58 to "TGT",
        42 to "REC YD",
        43 to "REC TD",
        72 to "FUM LOST",
        83 to "FG",
        86 to "XP",
        99 to "SACK",
        95 to "INT",
        96 to "FR",
        120 to "PTS ALL",
        127 to "YDS ALL",
    )

    // ------------------------------------------------------------------ free agents

    /**
     * The `x-fantasy-filter` header. ESPN takes this as a JSON *header*, not a query
     * parameter or a body — a `kona_player_info` request without it answers with the
     * entire player universe, which is megabytes and takes long enough to time out.
     *
     * @param slotIds lineup slots to include; the player must be eligible for one of them.
     * @param onWaivers true for the waiver wire, false for outright free agents.
     */
    fun playerFilter(
        slotIds: List<Int>,
        season: Int,
        scoringPeriodId: Int,
        limit: Int = 50,
        offset: Int = 0,
        onWaivers: Boolean? = null,
        sort: PlayerSort = PlayerSort.OWNERSHIP,
    ): String {
        val status = when (onWaivers) {
            null -> "\"FREEAGENT\",\"WAIVERS\""
            true -> "\"WAIVERS\""
            false -> "\"FREEAGENT\""
        }
        val slots = slotIds.joinToString(",")
        val sortClause = when (sort) {
            PlayerSort.OWNERSHIP ->
                "\"sortPercOwned\":{\"sortAsc\":false,\"sortPriority\":1},"
            // The stat id a weekly projection is filed under is the three parts of its
            // identity concatenated: source 1 (projected), the season, the period. There
            // is no separate field for it, which is why this looks like a magic number.
            PlayerSort.PROJECTION ->
                "\"sortAppliedStatTotal\":{\"sortAsc\":false,\"sortPriority\":1," +
                    "\"value\":\"1$season$scoringPeriodId\"},"
            PlayerSort.SEASON_TOTAL ->
                "\"sortAppliedStatTotal\":{\"sortAsc\":false,\"sortPriority\":1," +
                    "\"value\":\"0${season}0\"},"
        }
        return "{\"players\":{" +
            "\"filterStatus\":{\"value\":[$status]}," +
            "\"filterSlotIds\":{\"value\":[$slots]}," +
            sortClause +
            "\"limit\":$limit,\"offset\":$offset," +
            "\"filterRanksForScoringPeriodIds\":{\"value\":[$scoringPeriodId]}" +
            "}}"
    }

    enum class PlayerSort { OWNERSHIP, PROJECTION, SEASON_TOTAL }

    /** Roster slots a waiver search covers by default: the offensive skill positions. */
    val SEARCHABLE_SLOTS = listOf(0, 2, 4, 6, 16, 17)

    // ------------------------------------------------------------------ transactions

    /**
     * A lineup change, an add and a drop are all the same POST to the same path; only the
     * `type` and the `items` differ.
     *
     * Unverified against a real account — this session had no ESPN credentials to write
     * with, and a league you can read is not a league you can move players in. The shape
     * below is what the ESPN web app sends, and every failure path surfaces ESPN's own
     * error text to the screen rather than a generic one, precisely so the first real
     * attempt says what is wrong instead of "failed".
     */
    const val TYPE_LINEUP = "ROSTER"
    const val TYPE_FREEAGENT = "FREEAGENT"
    const val TYPE_WAIVER = "WAIVER"
}
