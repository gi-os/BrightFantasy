package com.gios.brightfantasy.model

import com.gios.brightfantasy.data.Espn

/**
 * What the app knows about a league, flattened out of ESPN's views.
 *
 * Everything here is plain data with no Android import, so the parser that fills it can
 * be compiled and asserted against off-device.
 */

/** League identity and the two numbers every other screen is relative to. */
data class LeagueInfo(
    val id: String,
    val season: Int,
    val name: String,
    val size: Int,
    /** The week whose games are being played. */
    val scoringPeriodId: Int,
    /** The week the schedule calls "now" — can differ from the above in the playoffs. */
    val matchupPeriodId: Int,
    val firstScoringPeriod: Int,
    val finalScoringPeriod: Int,
    val regularSeasonWeeks: Int,
    val playoffTeams: Int,
    val isActive: Boolean,
    /** Ordered starting slots, from `rosterSettings.lineupSlotCounts`. */
    val lineupSlots: List<SlotCount>,
    val scoringName: String,
) {
    val isPlayoffs: Boolean get() = matchupPeriodId > regularSeasonWeeks
}

data class SlotCount(val slotId: Int, val count: Int) {
    val name: String get() = Espn.slotName(slotId)
}

data class FantasyTeam(
    val id: Int,
    val name: String,
    val abbrev: String,
    val logoUrl: String?,
    val owners: List<String>,
    val wins: Int,
    val losses: Int,
    val ties: Int,
    val pointsFor: Double,
    val pointsAgainst: Double,
    val streakLength: Int,
    val streakType: String,
    val playoffSeed: Int,
    val waiverRank: Int,
    val divisionId: Int,
    val acquisitions: Int,
    val trades: Int,
    val moveToActive: Int,
) {
    val record: String get() = if (ties > 0) "$wins-$losses-$ties" else "$wins-$losses"

    val streak: String
        get() = when {
            streakLength <= 0 -> "—"
            streakType.equals("WIN", true) -> "W$streakLength"
            streakType.equals("LOSS", true) -> "L$streakLength"
            else -> "T$streakLength"
        }
}

/** One player as they sit on a roster in a given week. */
data class RosterSlot(
    val playerId: Int,
    val name: String,
    val slotId: Int,
    val positionId: Int,
    val proTeamId: Int,
    val eligibleSlots: List<Int>,
    /** Points scored this week so far. */
    val points: Double,
    /** ESPN's projection for this week. */
    val projected: Double,
    val seasonTotal: Double,
    val seasonAverage: Double,
    val injuryStatus: String,
    val percentOwned: Double,
    val positionalRank: Int,
    /** True once the player's real game has kicked off and the slot can no longer move. */
    val lineupLocked: Boolean,
    /** The week's box score, already filtered to the stats worth showing. */
    val statLine: List<Pair<String, Double>>,
    /** Kickoff of the player's NFL game this week, epoch millis, or 0 if unknown. */
    val kickoffMillis: Long,
    val opponentProTeamId: Int,
    /** True when the real game has finished, so the points are final. */
    val gameFinished: Boolean,
) {
    val starting: Boolean get() = Espn.isStarting(slotId)
    val slotName: String get() = Espn.slotName(slotId)
    val position: String get() = Espn.positionName(positionId)
    val proTeam: String get() = Espn.proTeam(proTeamId)

    /** ESPN reports "ACTIVE" and "NORMAL" for a healthy player; both mean nothing to show. */
    val injuryTag: String?
        get() = when (injuryStatus.uppercase()) {
            "ACTIVE", "NORMAL", "" -> null
            "QUESTIONABLE" -> "Q"
            "DOUBTFUL" -> "D"
            "OUT" -> "O"
            "INJURY_RESERVE" -> "IR"
            "SUSPENSION" -> "SUSP"
            "DAY_TO_DAY" -> "DTD"
            else -> injuryStatus.take(3)
        }

    /**
     * Whether this player still has points to come. Not the same as "not started" —
     * a player on bye or with no game this week is done before he begins.
     */
    val yetToPlay: Boolean get() = !gameFinished && kickoffMillis > 0
}

data class Roster(
    val teamId: Int,
    val week: Int,
    val slots: List<RosterSlot>,
) {
    /**
     * Whether there is a lineup here at all, as opposed to a roster of pure bench.
     *
     * A note worth keeping, because it was got wrong once: asking ESPN for a *past* week
     * really does return that week's lineup, and the starters do add up to the recorded
     * score. Verified against all twelve teams of a real week — every one matched to the
     * decimal. When a sum comes out low the cause is not ESPN's history, it is a starting
     * slot being counted as bench; see [com.gios.brightfantasy.data.Espn.isStarting],
     * where FLEX has a higher id than the bench does.
     *
     * The matchup header still reads the schedule's own `pointsByScoringPeriod` rather
     * than adding the roster up — it is one field instead of fifteen and it is right
     * before the roster has even been fetched.
     */
    val complete: Boolean get() = slots.any { it.starting }

    val starters: List<RosterSlot>
        get() = slots.filter { it.starting }.sortedBy { Espn.slotRank(it.slotId) }

    val bench: List<RosterSlot>
        get() = slots.filter { !it.starting }.sortedBy { Espn.slotRank(it.slotId) }

    val startersPoints: Double get() = starters.sumOf { it.points }
    val startersProjected: Double get() = starters.sumOf { it.projected }
    val yetToPlay: Int get() = starters.count { it.yetToPlay }

    /**
     * The best lineup that was legally available this week, for the "what you left on the
     * bench" line. Greedy over the slots in their read order, which is optimal here
     * because the flexible slots are strictly more permissive than the ones before them.
     */
    fun optimalPoints(lineupSlots: List<SlotCount>): Double {
        val available = slots.toMutableList()
        var total = 0.0
        val ordered = lineupSlots
            .filter { Espn.isStarting(it.slotId) }
            .sortedBy { Espn.slotRank(it.slotId) }
        for (slot in ordered) {
            repeat(slot.count) {
                val best = available
                    .filter { slot.slotId in it.eligibleSlots }
                    .maxByOrNull { it.points }
                    ?: return@repeat
                total += best.points
                available.remove(best)
            }
        }
        return total
    }
}

/** One side of a head-to-head. */
data class MatchupSide(
    val teamId: Int,
    val points: Double,
    val roster: Roster?,
)

data class Matchup(
    val id: Int,
    val matchupPeriodId: Int,
    val home: MatchupSide,
    val away: MatchupSide?,
    val winner: String,
    val playoffTier: String,
) {
    val isBye: Boolean get() = away == null
    val decided: Boolean get() = winner == "HOME" || winner == "AWAY"

    fun sideFor(teamId: Int): MatchupSide? = when (teamId) {
        home.teamId -> home
        away?.teamId -> away
        else -> null
    }

    fun opponentOf(teamId: Int): MatchupSide? = when (teamId) {
        home.teamId -> away
        away?.teamId -> home
        else -> null
    }

    fun involves(teamId: Int): Boolean = home.teamId == teamId || away?.teamId == teamId
}

/** A player on the wire, which is a roster slot without a roster. */
data class FreeAgent(
    val playerId: Int,
    val name: String,
    val positionId: Int,
    val proTeamId: Int,
    val eligibleSlots: List<Int>,
    val status: String,
    val percentOwned: Double,
    val percentStarted: Double,
    val percentChange: Double,
    val projected: Double,
    val lastWeek: Double,
    val seasonTotal: Double,
    val seasonAverage: Double,
    val injuryStatus: String,
    val positionalRank: Int,
) {
    val position: String get() = Espn.positionName(positionId)
    val proTeam: String get() = Espn.proTeam(proTeamId)
    val onWaivers: Boolean get() = status.equals("WAIVERS", true)
}

/** A completed league move, from the communication feed. */
data class LeagueMove(
    val id: String,
    val type: String,
    val teamId: Int,
    val date: Long,
    val added: List<String>,
    val dropped: List<String>,
    val bid: Int,
) {
    val label: String
        get() = when (type.uppercase()) {
            "WAIVER" -> "CLAIMED"
            "FREEAGENT" -> "ADDED"
            "TRADE_ACCEPT", "TRADE_UPHOLD" -> "TRADED"
            "DRAFT" -> "DRAFTED"
            "ROSTER" -> "LINEUP"
            else -> type.replace('_', ' ')
        }
}

/** One pick, for the draft recap. */
data class DraftPick(
    val overall: Int,
    val round: Int,
    val roundPick: Int,
    val teamId: Int,
    val playerId: Int,
    val bidAmount: Int,
    val keeper: Boolean,
)

/**
 * The state one refresh produces. Held as a whole so a half-loaded screen is never a
 * screen — either the week is on it or the previous week still is.
 */
data class LeagueState(
    val info: LeagueInfo,
    val teams: List<FantasyTeam>,
    val matchups: List<Matchup>,
    val fetchedAt: Long,
) {
    fun team(id: Int): FantasyTeam? = teams.firstOrNull { it.id == id }

    fun matchupFor(teamId: Int, week: Int): Matchup? =
        matchups.firstOrNull { it.matchupPeriodId == week && it.involves(teamId) }

    fun weeksPlayed(): List<Int> =
        matchups.map { it.matchupPeriodId }.distinct().sorted()

    /**
     * Standings, in the order ESPN would show them. Sorted here rather than trusted from
     * `playoffSeed`, which is 0 for every team until the league has played a week.
     */
    fun standings(): List<FantasyTeam> = teams.sortedWith(
        compareByDescending<FantasyTeam> { it.wins }
            .thenBy { it.losses }
            .thenByDescending { it.pointsFor },
    )

    /**
     * Power rankings: two-thirds how much you score, one-third whether you win. Points
     * for is the only signal in fantasy football that is not mostly schedule luck, but a
     * ranking that ignores the record entirely stops being a ranking of anything.
     */
    fun power(): List<Pair<FantasyTeam, Double>> {
        if (teams.isEmpty()) return emptyList()
        val games = teams.maxOf { it.wins + it.losses + it.ties }.coerceAtLeast(1)
        val maxPoints = teams.maxOf { it.pointsFor }.coerceAtLeast(0.01)
        return teams
            .map { team ->
                val winShare = (team.wins + team.ties * 0.5) / games
                val pointShare = team.pointsFor / maxPoints
                team to (pointShare * 2 + winShare) / 3 * 100
            }
            .sortedByDescending { it.second }
    }
}
