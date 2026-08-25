package com.gios.brightfantasy

import com.gios.brightfantasy.data.FantasyParser
import com.gios.brightfantasy.model.LeagueState
import com.gios.brightfantasy.model.SlotCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parser, against fixtures cut down from real ESPN responses.
 *
 * Trimmed by hand rather than captured wholesale: a real league payload is 1.8 MB and
 * nobody would read the diff when it changed. Every field kept here is one the parser
 * actually reads, arranged to reproduce the traps found while writing it.
 */
class ParserTest {

    // ------------------------------------------------------------------ league

    private val leagueJson = """
    {
      "seasonId": 2025,
      "scoringPeriodId": 15,
      "status": {
        "currentMatchupPeriod": 15, "firstScoringPeriod": 1,
        "finalScoringPeriod": 17, "latestScoringPeriod": 15, "isActive": true
      },
      "settings": {
        "name": "Shady J Football League",
        "size": 12,
        "scheduleSettings": { "matchupPeriodCount": 14, "playoffTeamCount": 6 },
        "rosterSettings": { "lineupSlotCounts": {
          "0":1,"1":0,"2":2,"3":0,"4":2,"5":0,"6":1,"7":0,"16":1,"17":1,"20":7,"21":1,"23":1
        } },
        "scoringSettings": { "scoringItems": [
          { "statId": 3, "points": 0.04 },
          { "statId": 53, "points": 0.5 }
        ] }
      }
    }
    """.trimIndent()

    @Test
    fun `league settings come through`() {
        val info = FantasyParser.parseLeague(leagueJson, "105491")!!
        assertEquals("Shady J Football League", info.name)
        assertEquals(2025, info.season)
        assertEquals(12, info.size)
        assertEquals(15, info.matchupPeriodId)
        assertEquals(14, info.regularSeasonWeeks)
        assertEquals(6, info.playoffTeams)
        assertTrue(info.isPlayoffs)
    }

    /**
     * `lineupSlotCounts` lists all 25 slots whatever the format, with a count of zero for
     * the ones the league does not use. Dropping the zeroes is what turns it into a
     * lineup; keeping them puts nine empty positions on the roster screen.
     */
    @Test
    fun `unused lineup slots are dropped and the rest are in football order`() {
        val info = FantasyParser.parseLeague(leagueJson, "1")!!
        assertEquals(
            listOf("QB", "RB", "WR", "TE", "FLEX", "D/ST", "K", "BE", "IR"),
            info.lineupSlots.map { it.name },
        )
        assertEquals(2, info.lineupSlots.first { it.name == "RB" }.count)
    }

    /** There is no "PPR" field; the reception rule is stat id 53 in a 200-entry array. */
    @Test
    fun `scoring type is read off the reception rule`() {
        assertEquals("Half PPR", FantasyParser.parseLeague(leagueJson, "1")!!.scoringName)
        val ppr = leagueJson.replace("\"statId\": 53, \"points\": 0.5", "\"statId\": 53, \"points\": 1.0")
        assertEquals("PPR", FantasyParser.parseLeague(ppr, "1")!!.scoringName)
        val standard = leagueJson.replace("\"statId\": 53, \"points\": 0.5", "\"statId\": 53, \"points\": 0.0")
        assertEquals("Standard", FantasyParser.parseLeague(standard, "1")!!.scoringName)
    }

    @Test
    fun `garbage parses to null rather than throwing`() {
        assertNull(FantasyParser.parseLeague("not json", "1"))
        assertTrue(FantasyParser.parseTeams("not json").isEmpty())
        assertTrue(FantasyParser.parseMatchups("not json").isEmpty())
    }

    // ------------------------------------------------------------------ teams

    private val teamsJson = """
    { "teams": [
      { "id": 1, "abbrev": "PT", "name": "Penis Toes", "playoffSeed": 8,
        "owners": ["{A51BEE97-3B23-4251-A3A7-E751957BFB34}"],
        "transactionCounter": { "acquisitions": 30, "trades": 1, "moveToActive": 44 },
        "record": { "overall": { "wins": 6, "losses": 8, "ties": 0,
          "pointsFor": 1544.0, "pointsAgainst": 1614.5,
          "streakLength": 1, "streakType": "LOSS" } } },
      { "id": 2, "location": "Old", "nickname": "Style", "name": "",
        "record": { "overall": { "wins": 10, "losses": 4, "ties": 0,
          "pointsFor": 1700.0, "pointsAgainst": 1500.0,
          "streakLength": 3, "streakType": "WIN" } } }
    ] }
    """.trimIndent()

    @Test
    fun `team record and streak`() {
        val teams = FantasyParser.parseTeams(teamsJson)
        assertEquals(2, teams.size)
        val one = teams.first { it.id == 1 }
        assertEquals("6-8", one.record)
        assertEquals("L1", one.streak)
        assertEquals(1544.0, one.pointsFor, 0.01)
        assertEquals(30, one.acquisitions)
    }

    /** ESPN dropped location/nickname for a single `name`, but old leagues still send both. */
    @Test
    fun `a team with no name falls back to location and nickname`() {
        val teams = FantasyParser.parseTeams(teamsJson)
        assertEquals("Old Style", teams.first { it.id == 2 }.name)
        // No abbrev in the payload either, so one is derived rather than left blank.
        assertEquals("OLD ", teams.first { it.id == 2 }.abbrev)
    }

    // ------------------------------------------------------------------ schedule

    private val scheduleJson = """
    { "schedule": [
      { "id": 1, "matchupPeriodId": 15, "winner": "HOME", "playoffTierType": "WINNERS_BRACKET",
        "home": { "teamId": 1, "totalPoints": 1544.0, "pointsByScoringPeriod": { "15": 140.6 } },
        "away": { "teamId": 2, "totalPoints": 1700.0, "pointsByScoringPeriod": { "15": 93.9 } } },
      { "id": 2, "matchupPeriodId": 15, "winner": "UNDECIDED", "playoffTierType": "WINNERS_BRACKET",
        "home": { "teamId": 3, "pointsByScoringPeriod": { "15": 120.8 } } },
      { "id": 3, "matchupPeriodId": 16, "winner": "UNDECIDED", "playoffTierType": "NONE",
        "home": { "teamId": 1, "pointsByScoringPeriod": { "16": 0.0 } },
        "away": { "teamId": 3, "pointsByScoringPeriod": { "16": 0.0 } } }
    ] }
    """.trimIndent()

    /**
     * `totalPoints` is the season figure in this view and the week's in others.
     * `pointsByScoringPeriod` is the one that always means the week.
     */
    @Test
    fun `weekly score comes from pointsByScoringPeriod, not totalPoints`() {
        val week15 = FantasyParser.parseMatchups(scheduleJson, 15)
        assertEquals(2, week15.size)
        assertEquals(140.6, week15[0].home.points, 0.01)
        assertEquals(93.9, week15[0].away!!.points, 0.01)
    }

    /** A playoff bye is a matchup with no `away` key at all. */
    @Test
    fun `a bye has no opponent`() {
        val bye = FantasyParser.parseMatchups(scheduleJson, 15).first { it.id == 2 }
        assertTrue(bye.isBye)
        assertNull(bye.opponentOf(3))
        assertTrue(bye.involves(3))
    }

    /**
     * `mMatchupScore` with a `scoringPeriodId` inlines a roster whose players have no
     * names on them. The parser must not hand that back as a roster.
     */
    @Test
    fun `the schedule never carries rosters`() {
        assertTrue(FantasyParser.parseMatchups(scheduleJson).all { it.home.roster == null })
    }

    // ------------------------------------------------------------------ rosters

    /**
     * The stats array holds actual and projected for the same week, plus season totals
     * and averages, in no guaranteed order. Indexing it — which is what most examples do
     * — gets a projection instead of a result about half the time, silently. Here the
     * projection is deliberately first.
     */
    private val rosterJson = """
    { "teams": [ { "id": 1, "roster": { "entries": [
      { "lineupSlotId": 0, "injuryStatus": "ACTIVE", "playerPoolEntry": {
        "lineupLocked": true,
        "ratings": { "0": { "positionalRanking": 4 } },
        "player": { "id": 100, "fullName": "Josh Allen", "defaultPositionId": 1,
          "proTeamId": 2, "eligibleSlots": [0, 7, 20, 21],
          "injuryStatus": "ACTIVE",
          "ownership": { "percentOwned": 99.9 },
          "stats": [
            { "scoringPeriodId": 15, "statSourceId": 1, "statSplitTypeId": 1, "appliedTotal": 19.1 },
            { "scoringPeriodId": 15, "statSourceId": 0, "statSplitTypeId": 1, "appliedTotal": 24.4,
              "stats": { "3": 250.0, "4": 2.0, "20": 1.0, "24": 40.0 } },
            { "scoringPeriodId": 0, "statSourceId": 0, "statSplitTypeId": 0, "appliedTotal": 328.4 },
            { "scoringPeriodId": 0, "statSourceId": 0, "statSplitTypeId": 2, "appliedTotal": 21.9 }
          ] } } },
      { "lineupSlotId": 2, "injuryStatus": "QUESTIONABLE", "playerPoolEntry": {
        "player": { "id": 101, "fullName": "Bench Back", "defaultPositionId": 2,
          "proTeamId": 8, "eligibleSlots": [2, 3, 23, 20, 21],
          "stats": [
            { "scoringPeriodId": 15, "statSourceId": 0, "statSplitTypeId": 1, "appliedTotal": 4.3 }
          ] } } },
      { "lineupSlotId": 20, "playerPoolEntry": {
        "player": { "id": 102, "fullName": "Big Bench", "defaultPositionId": 2,
          "proTeamId": 8, "eligibleSlots": [2, 3, 23, 20, 21],
          "stats": [
            { "scoringPeriodId": 15, "statSourceId": 0, "statSplitTypeId": 1, "appliedTotal": 30.0 }
          ] } } }
    ] } } ] }
    """.trimIndent()

    private val proScheduleJson = """
    { "settings": { "proTeams": [
      { "id": 2, "abbrev": "BUF", "byeWeek": 7, "proGamesByScoringPeriod": {
        "15": [ { "id": 1, "date": 1766340000000, "homeProTeamId": 2,
                  "awayProTeamId": 20, "statsOfficial": true, "startTimeTBD": false } ] } },
      { "id": 8, "abbrev": "DET", "byeWeek": 15, "proGamesByScoringPeriod": {} }
    ] } }
    """.trimIndent()

    @Test
    fun `the right stat entry is picked out of the pile`() {
        val pro = FantasyParser.parseProSchedule(proScheduleJson)
        val roster = FantasyParser.parseRoster(rosterJson, 1, 15, pro)!!
        val qb = roster.slots.first { it.playerId == 100 }
        assertEquals(24.4, qb.points, 0.01)      // actual, though projected came first
        assertEquals(19.1, qb.projected, 0.01)
        assertEquals(328.4, qb.seasonTotal, 0.01)
        assertEquals(21.9, qb.seasonAverage, 0.01)
        assertEquals(4, qb.positionalRank)
        assertTrue(qb.lineupLocked)
    }

    @Test
    fun `the week box score drops the zeroes and keeps reading order`() {
        val roster = FantasyParser.parseRoster(rosterJson, 1, 15, null)!!
        val qb = roster.slots.first { it.playerId == 100 }
        assertEquals(
            listOf("PASS YD" to 250.0, "PASS TD" to 2.0, "INT" to 1.0, "RUSH YD" to 40.0),
            qb.statLine,
        )
    }

    @Test
    fun `starters and bench split on the slot, not the position`() {
        val roster = FantasyParser.parseRoster(rosterJson, 1, 15, null)!!
        assertEquals(listOf(100, 101), roster.starters.map { it.playerId })
        assertEquals(listOf(102), roster.bench.map { it.playerId })
        assertEquals(28.7, roster.startersPoints, 0.01)
    }

    /**
     * A player on bye and a player who has not kicked off both show zero points. Only the
     * pro schedule distinguishes them, and getting it backwards makes a bye week read as
     * four starters still to play.
     */
    @Test
    fun `a bye is finished, an unplayed game is not`() {
        val pro = FantasyParser.parseProSchedule(proScheduleJson)
        val roster = FantasyParser.parseRoster(rosterJson, 1, 15, pro)!!
        val played = roster.slots.first { it.playerId == 100 }
        val onBye = roster.slots.first { it.playerId == 101 }

        assertTrue(played.gameFinished)
        assertFalse(played.yetToPlay)
        assertEquals(20, played.opponentProTeamId)

        assertEquals(0L, onBye.kickoffMillis)
        assertTrue(onBye.gameFinished)
        assertFalse(onBye.yetToPlay)
        assertEquals(0, roster.yetToPlay)
    }

    @Test
    fun `a live game counts as still to play`() {
        val live = proScheduleJson.replace("\"statsOfficial\": true", "\"statsOfficial\": false")
        val pro = FantasyParser.parseProSchedule(live)
        val roster = FantasyParser.parseRoster(rosterJson, 1, 15, pro)!!
        assertTrue(roster.slots.first { it.playerId == 100 }.yetToPlay)
        assertEquals(1, roster.yetToPlay)
    }

    @Test
    fun `the entry injury status wins over the player one`() {
        val roster = FantasyParser.parseRoster(rosterJson, 1, 15, null)!!
        assertNull(roster.slots.first { it.playerId == 100 }.injuryTag)
        assertEquals("Q", roster.slots.first { it.playerId == 101 }.injuryTag)
    }

    /** The 30-point bench back should have started at RB; that is the whole point. */
    @Test
    fun `optimal lineup is what you should have started`() {
        val roster = FantasyParser.parseRoster(rosterJson, 1, 15, null)!!
        val slots = listOf(SlotCount(0, 1), SlotCount(2, 1), SlotCount(20, 7))
        assertEquals(54.4, roster.optimalPoints(slots), 0.01)
        assertTrue(roster.optimalPoints(slots) > roster.startersPoints)
    }

    // ------------------------------------------------------------------ activity

    /**
     * On a waiver claim `from` is the winning bid, not a team id. On a trade it is a team
     * id. Reading one as the other puts a team number in the price column.
     */
    @Test
    fun `a waiver bid is not a team id`() {
        val json = """
        { "topics": [ { "id": "t1", "date": 1700000000000, "messages": [
          { "messageTypeId": 180, "targetId": 100, "to": 3, "from": 17 },
          { "messageTypeId": 179, "targetId": 101, "to": 3 }
        ] } ] }
        """.trimIndent()
        val names = mapOf(100 to "Josh Allen", 101 to "Bench Back")
        val moves = FantasyParser.parseActivity(json) { names[it] }
        assertEquals(1, moves.size)
        val move = moves.first()
        assertEquals("CLAIMED", move.label)
        assertEquals(3, move.teamId)
        assertEquals(17, move.bid)
        assertEquals(listOf("Josh Allen"), move.added)
        assertEquals(listOf("Bench Back"), move.dropped)
    }

    @Test
    fun `an unknown player id still produces a row`() {
        val json = """
        { "topics": [ { "id": "t", "date": 1, "messages": [
          { "messageTypeId": 178, "targetId": 999, "to": 1 } ] } ] }
        """.trimIndent()
        val moves = FantasyParser.parseActivity(json) { null }
        assertEquals(listOf("#999"), moves.single().added)
        assertEquals("ADDED", moves.single().label)
    }

    // ------------------------------------------------------------------ derived

    @Test
    fun `standings sort by record then points`() {
        val info = FantasyParser.parseLeague(leagueJson, "1")!!
        val state = LeagueState(
            info,
            FantasyParser.parseTeams(teamsJson),
            FantasyParser.parseMatchups(scheduleJson),
            0L,
        )
        assertEquals(listOf(2, 1), state.standings().map { it.id })
        assertEquals(listOf(15, 16), state.weeksPlayed())
        assertNotNull(state.matchupFor(1, 15))
        assertEquals(2, state.matchupFor(1, 15)!!.opponentOf(1)!!.teamId)
    }

    @Test
    fun `power ranking weights points above record and stays ordered`() {
        val info = FantasyParser.parseLeague(leagueJson, "1")!!
        val state = LeagueState(info, FantasyParser.parseTeams(teamsJson), emptyList(), 0L)
        val power = state.power()
        assertEquals(2, power.size)
        assertEquals(2, power.first().first.id)
        assertTrue(power.first().second >= power.last().second)
    }

    @Test
    fun `an empty league does not divide by zero`() {
        val info = FantasyParser.parseLeague(leagueJson, "1")!!
        val state = LeagueState(info, emptyList(), emptyList(), 0L)
        assertTrue(state.power().isEmpty())
        assertTrue(state.standings().isEmpty())
    }
}
