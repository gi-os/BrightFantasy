package com.gios.brightfantasy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gios.brightfantasy.hw.WheelScroll
import com.gios.brightfantasy.model.LeagueState
import com.gios.brightfantasy.model.Roster
import com.gios.brightfantasy.model.RosterSlot
import com.gios.brightfantasy.ui.theme.Dim
import com.gios.brightfantasy.ui.theme.Faint

/**
 * Your team, and the one place a lineup gets changed.
 *
 * Start/sit is tap-one-then-tap-the-other rather than drag and drop. Drag needs a long
 * press to pick up, a scroll that does not fight it, and a drop target you can see under
 * your own thumb — three things a 3.9" panel makes hard and a wheel-scrolled list makes
 * harder. Two taps has none of those problems and works from the wheel too.
 */
@Composable
fun RosterScreen(
    roster: Roster?,
    league: LeagueState?,
    week: Int,
    showProjections: Boolean,
    editable: Boolean,
    onStepWeek: (Int) -> Unit,
    onSwap: (RosterSlot, RosterSlot) -> Unit,
    /** Null when the swap is legal; the reason otherwise. Checked before anything is sent. */
    swapProblem: (RosterSlot, RosterSlot) -> String?,
    onPlayer: (RosterSlot) -> Unit,
) {
    if (roster == null || league == null) {
        EmptyState("No roster for this week.")
        return
    }

    var picked by remember { mutableStateOf<RosterSlot?>(null) }
    var refused by remember { mutableStateOf<String?>(null) }

    val listState = rememberLazyListState()
    WheelScroll(listState)

    fun tap(slot: RosterSlot) {
        val first = picked
        when {
            !editable -> onPlayer(slot)
            first == null -> {
                picked = slot
                refused = null
            }
            first.playerId == slot.playerId -> {
                // Tapping the same player again is how you change your mind, and it has
                // to be, because there is nowhere else on this screen that is not a row.
                picked = null
                refused = null
            }
            else -> {
                val problem = swapProblem(first, slot)
                if (problem != null) {
                    refused = problem
                    picked = null
                } else {
                    onSwap(first, slot)
                    picked = null
                    refused = null
                }
            }
        }
    }

    val played = league.weeksPlayed()
    val optimal = roster.optimalPoints(league.info.lineupSlots)

    LazyColumn(Modifier.fillMaxSize(), listState) {
        item {
            WeekBar(
                week = week,
                label = "WEEK $week",
                canGoBack = played.isEmpty() || week > played.first(),
                canGoForward = played.isEmpty() || week < played.last(),
                onStep = onStepWeek,
            )
            Rule()
            StatStrip(
                listOf(
                    "Started" to pts(roster.startersPoints),
                    "Best possible" to pts(optimal),
                    "Left on bench" to pts((optimal - roster.startersPoints).coerceAtLeast(0.0)),
                ),
            )
            Rule()
        }

        picked?.let { slot ->
            item { Banner("Now tap who ${shortLabel(slot)} should swap with.") }
        }
        refused?.let { message ->
            item { Banner(message) }
        }

        item { SectionHeader("Starters") }
        items(roster.starters.size) { index ->
            val slot = roster.starters[index]
            PlayerRow(
                slot = slot,
                showProjection = showProjections,
                selected = picked?.playerId == slot.playerId,
                onClick = { tap(slot) },
                onLongClick = { onPlayer(slot) },
            )
        }

        item {
            Rule()
            SectionHeader("Bench")
        }
        items(roster.bench.size) { index ->
            val slot = roster.bench[index]
            PlayerRow(
                slot = slot,
                showProjection = showProjections,
                selected = picked?.playerId == slot.playerId,
                onClick = { tap(slot) },
                onLongClick = { onPlayer(slot) },
            )
        }

        item {
            Rule()
            Text(
                if (editable) {
                    "Tap a player, then tap who they swap with. Hold a player for their week."
                } else {
                    "Past weeks are read-only."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Faint,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
        }
    }
}

/** Somebody else's roster: the same list, nothing tappable but the players. */
@Composable
fun ReadOnlyRoster(roster: Roster?, showProjections: Boolean, onPlayer: (RosterSlot) -> Unit) {
    if (roster == null) {
        EmptyState("Loading…")
        return
    }
    val listState = rememberLazyListState()
    WheelScroll(listState)
    LazyColumn(Modifier.fillMaxSize(), listState) {
        item { SectionHeader("Starters") }
        items(roster.starters.size) { i ->
            PlayerRow(roster.starters[i], showProjections, onClick = { onPlayer(roster.starters[i]) })
        }
        item {
            Rule()
            SectionHeader("Bench")
        }
        items(roster.bench.size) { i ->
            PlayerRow(roster.bench[i], showProjections, onClick = { onPlayer(roster.bench[i]) })
        }
    }
}

@Composable
private fun Banner(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A1A))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Color.White)
    }
}

private fun shortLabel(slot: RosterSlot): String =
    slot.name.split(' ').lastOrNull()?.takeIf { it.isNotBlank() } ?: slot.name
