package com.gios.brightfantasy.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gios.brightfantasy.data.Espn
import com.gios.brightfantasy.hw.WheelScroll
import com.gios.brightfantasy.model.FreeAgent
import com.gios.brightfantasy.model.Roster
import com.gios.brightfantasy.model.RosterSlot
import com.gios.brightfantasy.ui.theme.Dim
import com.gios.brightfantasy.ui.theme.Faint

/** The position filters, in the order a wire is actually searched. */
private val FILTERS: List<Pair<String, List<Int>>> = listOf(
    "ALL" to Espn.SEARCHABLE_SLOTS,
    "QB" to listOf(0),
    "RB" to listOf(2),
    "WR" to listOf(4),
    "TE" to listOf(6),
    "FLEX" to listOf(2, 4, 6),
    "D/ST" to listOf(16),
    "K" to listOf(17),
)

/**
 * Free agents and the waiver wire.
 *
 * ESPN's own app sorts this by percent-owned by default, which is a popularity contest
 * and mostly lists players everyone already has. The projection sort is one tap away and
 * is what actually answers "who should I pick up", so the preference is remembered.
 */
@Composable
fun WireScreen(
    agents: List<FreeAgent>,
    roster: Roster?,
    byProjection: Boolean,
    onLoad: (List<Int>, Boolean?) -> Unit,
    onToggleSort: () -> Unit,
    onAdd: (FreeAgent) -> Unit,
) {
    var filter by remember { mutableStateOf(0) }
    var waiversOnly by remember { mutableStateOf(false) }

    LaunchedEffect(filter, waiversOnly, byProjection) {
        onLoad(FILTERS[filter].second, if (waiversOnly) true else null)
    }

    val listState = rememberLazyListState()
    WheelScroll(listState)

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FILTERS.forEachIndexed { index, (label, _) ->
                Chip(label, selected = filter == index) { filter = index }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Chip(
                if (byProjection) "BY PROJECTION" else "BY OWNERSHIP",
                selected = byProjection,
                onClick = onToggleSort,
            )
            Chip("WAIVERS ONLY", selected = waiversOnly) { waiversOnly = !waiversOnly }
        }
        Rule()

        if (agents.isEmpty()) {
            EmptyState("Nobody available with that filter.")
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize(), listState) {
            items(agents.size) { i ->
                AgentRow(agents[i], byProjection) { onAdd(agents[i]) }
            }
        }
    }
}

@Composable
private fun AgentRow(agent: FreeAgent, byProjection: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    agent.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (agent.onWaivers) {
                    Text(
                        " W",
                        style = MaterialTheme.typography.labelSmall,
                        color = Dim,
                    )
                }
            }
            Text(
                buildString {
                    append(agent.position).append(" · ").append(agent.proTeam)
                    append(" · ").append(agent.percentOwned.toInt()).append("% own")
                    // The trend is the useful half. Ownership tells you where a player
                    // has been; the change over the last day tells you where the rest of
                    // the league is going, which is what a waiver run actually is.
                    if (agent.percentChange >= 1.0) {
                        append(" ↑").append(agent.percentChange.toInt())
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(58.dp)) {
            Text(
                pts(if (byProjection) agent.projected else agent.seasonAverage),
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                maxLines = 1,
            )
            Caption(if (byProjection) "PROJ" else "AVG")
        }
    }
    Rule()
}

/**
 * Who to drop for the player being added.
 *
 * ESPN would accept an add with no drop and then refuse it for a full roster, so the
 * choice is made here first. Anyone on IR or already locked is filtered out — dropping a
 * locked player is refused at the other end and there is no reason to offer it.
 */
@Composable
fun DropPicker(
    add: FreeAgent,
    roster: Roster?,
    onPick: (RosterSlot?) -> Unit,
    onCancel: () -> Unit,
) {
    val listState = rememberLazyListState()
    WheelScroll(listState)
    val droppable = roster?.slots
        ?.filter { !it.lineupLocked && it.slotId != Espn.SLOT_IR }
        ?.sortedBy { it.seasonAverage }
        .orEmpty()

    Column(Modifier.fillMaxSize()) {
        Text(
            "Adding ${add.name}. Who goes?",
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
            modifier = Modifier.padding(16.dp),
        )
        Rule()
        LazyColumn(Modifier.weight(1f), listState) {
            item {
                MenuRow(
                    label = "Nobody — add without dropping",
                    sub = "Fails if the roster is full",
                    dim = true,
                    onClick = { onPick(null) },
                )
                Rule()
                // Worst average first: the answer to "who goes" is almost always at the
                // top of this list, and scrolling to find it is the whole friction.
                SectionHeader("Lowest scoring first")
            }
            items(droppable.size) { i ->
                MenuRow(
                    label = droppable[i].name,
                    sub = "${droppable[i].slotName} · ${pts(droppable[i].seasonAverage)} avg",
                    detail = pts(droppable[i].seasonTotal),
                    onClick = { onPick(droppable[i]) },
                )
            }
        }
        Rule()
        Text(
            "Cancel",
            style = MaterialTheme.typography.bodyLarge,
            color = Faint,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onCancel)
                .padding(16.dp),
        )
    }
}
