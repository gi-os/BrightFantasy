package com.gios.brightfantasy.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gios.brightfantasy.data.FantasyRepository
import com.gios.brightfantasy.hw.WheelScroll
import com.gios.brightfantasy.model.LeagueState
import com.gios.brightfantasy.model.RosterSlot
import com.gios.brightfantasy.ui.theme.Dim
import com.gios.brightfantasy.ui.theme.Faint

/**
 * The head-to-head. The screen the app opens on, and the one it exists for.
 *
 * Both lineups side by side rather than one above the other. The comparison people
 * actually make is positional — my running backs against yours — and stacking the two
 * teams means scrolling between the halves of every comparison.
 */
@Composable
fun MatchupScreen(
    detail: FantasyRepository.MatchupDetail?,
    league: LeagueState?,
    week: Int,
    showProjections: Boolean,
    onStepWeek: (Int) -> Unit,
    onPlayer: (RosterSlot) -> Unit,
) {
    if (detail == null || league == null) {
        EmptyState("No matchup for this week yet.")
        return
    }

    val mine = detail.mine
    val theirs = detail.theirs
    val myTeam = league.team(detail.teamId)
    val theirTeam = theirs?.let { league.team(it.teamId) }
    val played = league.weeksPlayed()

    val listState = rememberLazyListState()
    WheelScroll(listState)

    LazyColumn(Modifier.fillMaxSize(), listState) {
        item {
            WeekBar(
                week = week,
                label = weekLabel(league, week),
                canGoBack = played.isEmpty() || week > played.first(),
                canGoForward = played.isEmpty() || week < played.last(),
                onStep = onStepWeek,
            )
            Rule()
        }
        item {
            ScoreHead(
                left = myTeam,
                leftPoints = mine?.points ?: 0.0,
                right = theirTeam,
                rightPoints = theirs?.points ?: 0.0,
                leftNote = sideNote(detail.myRoster, showProjections),
                rightNote = sideNote(detail.theirRoster, showProjections),
            )
            Rule()
        }

        if (detail.matchup.isBye) {
            item { EmptyState("Bye week — no opponent.") }
        }

        // One row per starting slot, both teams on it. The slot label sits in the middle
        // because that is the axis the comparison is on; putting it on the left would
        // make the right-hand team's rows unlabelled.
        val myStarters = detail.myRoster?.starters.orEmpty()
        val theirStarters = detail.theirRoster?.starters.orEmpty()
        val rows = maxOf(myStarters.size, theirStarters.size)
        if (rows > 0) {
            item { SectionHeader("Starters") }
            items(rows) { index ->
                FaceOff(
                    left = myStarters.getOrNull(index),
                    right = theirStarters.getOrNull(index),
                    onLeft = onPlayer,
                    onRight = onPlayer,
                )
            }
        }

        val myBench = detail.myRoster?.bench.orEmpty()
        if (myBench.isNotEmpty()) {
            item {
                Rule()
                SectionHeader("Your bench")
            }
            items(myBench.size) { index ->
                PlayerRow(
                    slot = myBench[index],
                    showProjection = showProjections,
                    onClick = { onPlayer(myBench[index]) },
                )
            }
        }

        item { Column(Modifier.padding(bottom = 24.dp)) {} }
    }
}

/**
 * "9 to play · proj 118.4" — the two things that decide whether a lead means anything.
 *
 * A twenty-point lead with two starters left is a different afternoon from a twenty-point
 * lead with nine, and the raw scores say nothing about which one you are having.
 */
private fun sideNote(
    roster: com.gios.brightfantasy.model.Roster?,
    showProjections: Boolean,
): String? {
    if (roster == null) return null
    val left = roster.yetToPlay
    val parts = mutableListOf<String>()
    if (left > 0) parts += if (left == 1) "1 to play" else "$left to play"
    if (showProjections) parts += "proj ${pts(roster.startersProjected)}"
    return parts.joinToString(" · ").takeIf { it.isNotEmpty() }
}

private fun weekLabel(league: LeagueState, week: Int): String {
    val info = league.info
    val playoff = week > info.regularSeasonWeeks
    return if (playoff) "WEEK $week · PLAYOFFS" else "WEEK $week"
}

/** One slot, two players, the label between them. */
@Composable
private fun FaceOff(
    left: RosterSlot?,
    right: RosterSlot?,
    onLeft: (RosterSlot) -> Unit,
    onRight: (RosterSlot) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Half(left, alignEnd = false, modifier = Modifier.weight(1f)) { left?.let(onLeft) }
        Text(
            left?.slotName ?: right?.slotName ?: "",
            style = MaterialTheme.typography.labelSmall,
            color = Faint,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        Half(right, alignEnd = true, modifier = Modifier.weight(1f)) { right?.let(onRight) }
    }
}

@Composable
private fun Half(
    slot: RosterSlot?,
    alignEnd: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier.let { if (slot != null) it.clickableRow(onClick) else it },
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
    ) {
        Text(
            slot?.let { shortName(it.name) } ?: "—",
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            buildString {
                append(slot?.let { pts(it.points) } ?: "0.0")
                slot?.injuryTag?.let { append("  ").append(it) }
            },
            style = MaterialTheme.typography.bodyMedium,
            // Grey while the real game is still running, white once it cannot change.
            color = if (slot?.yetToPlay == true) Dim else Color.White,
            maxLines = 1,
        )
    }
}

private fun Modifier.clickableRow(onClick: () -> Unit): Modifier =
    androidx.compose.foundation.clickable(onClick = onClick)

/**
 * "J. Gibbs" — a full name does not fit in half of a 3.9" screen and truncating it puts
 * the ellipsis exactly where the surname is.
 */
private fun shortName(full: String): String {
    val parts = full.trim().split(' ').filter { it.isNotBlank() }
    if (parts.size < 2) return full
    // A team defence is "Broncos D/ST", where the first word is the identifying one.
    if (parts.last().contains("D/ST")) return parts.first()
    return "${parts.first().first()}. ${parts.drop(1).joinToString(" ")}"
}
