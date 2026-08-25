package com.gios.brightfantasy

import com.gios.brightfantasy.data.Espn
import com.gios.brightfantasy.data.Setup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** URL building, the slot tables, and the setup payloads. All pure, all cheap. */
class EspnTest {

    @Test
    fun `read url carries every parameter in order`() {
        assertEquals(
            "https://lm-api-reads.fantasy.espn.com/apis/v3/games/ffl/seasons/2025" +
                "/segments/0/leagues/105491?view=mTeam&view=mRoster" +
                "&scoringPeriodId=5&forTeamId=3",
            Espn.readUrl(2025, "105491", listOf("mTeam", "mRoster"), 5, 3),
        )
    }

    @Test
    fun `writes go to the write host`() {
        assertTrue(Espn.writeUrl(2025, "1").startsWith("https://lm-api-writes."))
        assertTrue(Espn.writeUrl(2025, "1").endsWith("/transactions/"))
    }

    /**
     * The NFL year rolls over in the spring. A January game belongs to the previous
     * season, and getting this wrong points every request at a league that has no data
     * in it yet — which answers 200 with an empty scoreboard rather than an error.
     */
    @Test
    fun `season follows the football calendar, not the january one`() {
        assertEquals(2026, Espn.currentSeason(LocalDate.of(2026, 8, 24)))
        assertEquals(2026, Espn.currentSeason(LocalDate.of(2026, 6, 1)))
        assertEquals(2025, Espn.currentSeason(LocalDate.of(2026, 5, 31)))
        assertEquals(2025, Espn.currentSeason(LocalDate.of(2026, 1, 5)))
    }

    @Test
    fun `bench and IR are not starting slots`() {
        assertTrue(Espn.isStarting(0))
        assertTrue(Espn.isStarting(23))
        assertFalse(Espn.isStarting(Espn.SLOT_BENCH))
        assertFalse(Espn.isStarting(Espn.SLOT_IR))
        assertEquals("FLEX", Espn.slotName(23))
        assertEquals("BE", Espn.slotName(Espn.SLOT_BENCH))
    }

    @Test
    fun `lineup reads in football order, not numeric order`() {
        val order = listOf(0, 2, 4, 6, 23, 16, 17).sortedBy { Espn.slotRank(it) }
        assertEquals(listOf(0, 2, 4, 6, 23, 16, 17), order)
    }

    /**
     * The projection sort key is source, season and period concatenated into one string.
     * There is no field that spells it out, so a typo here is a filter ESPN silently
     * ignores rather than rejects.
     */
    @Test
    fun `player filter builds the composite projection sort key`() {
        val filter = Espn.playerFilter(
            slotIds = listOf(0, 2),
            season = 2025,
            scoringPeriodId = 15,
            sort = Espn.PlayerSort.PROJECTION,
        )
        assertTrue(filter.contains("\"filterSlotIds\":{\"value\":[0,2]}"))
        assertTrue(filter.contains("\"1202515\""))
        assertTrue(filter.contains("\"FREEAGENT\",\"WAIVERS\""))
    }

    @Test
    fun `waiver-only filter drops free agents`() {
        val filter = Espn.playerFilter(listOf(0), 2025, 15, onWaivers = true)
        assertTrue(filter.contains("[\"WAIVERS\"]"))
        assertFalse(filter.contains("FREEAGENT"))
    }

    // ------------------------------------------------------------------ setup

    @Test
    fun `setup url payload decodes both cookies`() {
        val p = Setup.parse(
            "brightfantasy://setup?league=105491&swid=%7BABC-123%7D&s2=xy%2Bz&season=2025&team=3",
        )!!
        assertEquals("105491", p.leagueId)
        assertEquals("{ABC-123}", p.swid)
        assertEquals("xy+z", p.espnS2)
        assertEquals(2025, p.season)
        assertEquals(3, p.teamId)
    }

    /**
     * An unbraced SWID is accepted by ESPN and then treated as logged out — a 200 with a
     * public-league view of a private league, which is the most confusing failure this
     * app can have. So it is braced on the way in, however it arrives.
     */
    @Test
    fun `swid is always braced`() {
        assertEquals("{ABC}", Setup.normaliseSwid("ABC"))
        assertEquals("{ABC}", Setup.normaliseSwid("{ABC}"))
        assertEquals("{ABC}", Setup.normaliseSwid("%7BABC%7D"))
        assertEquals("{ABC}", Setup.normaliseSwid("  {ABC}  "))
    }

    /**
     * `espn_s2` legitimately contains `+`, and `URLDecoder` would turn it into a space —
     * producing a different session that ESPN refuses.
     */
    @Test
    fun `percent decoding leaves plus alone`() {
        assertEquals("a+b c", Setup.decode("a+b%20c"))
        assertEquals("no-escapes", Setup.decode("no-escapes"))
        assertEquals("100%", Setup.decode("100%"))
    }

    @Test
    fun `json and espn urls are both accepted`() {
        val json = Setup.parse("""{"league":"999","swid":"DEF-456","s2":"tok"}""")!!
        assertEquals("999", json.leagueId)
        assertEquals("{DEF-456}", json.swid)
        assertEquals("tok", json.espnS2)

        val url = Setup.parse(
            "https://fantasy.espn.com/football/team?leagueId=105491&seasonId=2025&teamId=4",
        )!!
        assertEquals("105491", url.leagueId)
        assertEquals(2025, url.season)
        assertEquals(4, url.teamId)

        assertEquals("105491", Setup.parse("105491")?.leagueId)
    }

    @Test
    fun `nonsense is rejected rather than half-parsed`() {
        assertNull(Setup.parse("hello there"))
        assertNull(Setup.parse(""))
        assertNull(Setup.parse("   "))
    }
}
