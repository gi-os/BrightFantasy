package com.gios.brightfantasy.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.unit.dp
import com.gios.brightfantasy.hw.WheelScroll
import com.gios.brightfantasy.model.RosterSlot
import com.gios.brightfantasy.ui.theme.Dim
import com.gios.brightfantasy.ui.theme.Faint
import com.gios.brightfantasy.util.Fmt
import java.time.ZoneId

/**
 * One player: this week's box score, the season so far, and every week of it.
 *
 * The game log is drawn as bars rather than listed as numbers. Seventeen rows of "week 4:
 * 12.8" is a table nobody reads on a phone this size, and the only question anyone asks
 * of a game log — is this player consistent or is he one big week — is a shape question.
 */
@Composable
fun PlayerScreen(
    slot: RosterSlot,
    log: List<Pair<Int, Double>>,
    currentWeek: Int,
) {
    val listState = rememberLazyListState()
    WheelScroll(listState)

    LazyColumn(Modifier.fillMaxSize(), listState) {
        item {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(
                    slot.name,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                )
                Text(
                    buildString {
                        append(slot.position).append(" · ").append(slot.proTeam)
                        append(" · ").append(slot.slotName)
                        slot.injuryTag?.let { append(" · ").append(it) }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Dim,
                )
            }
            Rule()
            StatStrip(
                listOf(
                    "This week" to pts(slot.points),
                    "Projected" to pts(slot.projected),
                    "Season" to pts(slot.seasonTotal),
                    "Avg" to pts(slot.seasonAverage),
                ),
            )
            Rule()
        }

        item {
            val kickoff = slot.kickoffMillis
            val line = when {
                slot.proTeamId == 0 -> "Free agent"
                kickoff == 0L -> "On bye this week"
                slot.gameFinished -> "Final"
                else -> "Kickoff ${Fmt.dayTime(kickoff, ZoneId.systemDefault())}"
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(line, style = MaterialTheme.typography.bodyMedium, color = Dim)
                if (slot.percentOwned > 0) {
                    Text(
                        "  ·  ${slot.percentOwned.toInt()}% owned",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Faint,
                    )
                }
            }
            Rule()
        }

        if (slot.statLine.isNotEmpty()) {
            item { SectionHeader("This week") }
            items(slot.statLine.size) { i ->
                val (label, value) = slot.statLine[i]
                MenuRow(
                    label = label,
                    // Yardage and counts are whole numbers in a payload full of doubles;
                    // "132.0 receiving yards" is noise.
                    detail = if (value % 1.0 == 0.0) value.toInt().toString() else pts(value),
                )
            }
        }

        if (log.isNotEmpty()) {
            item {
                Rule()
                SectionHeader("Every week")
                GameLog(log, currentWeek)
            }
        }
    }
}

/**
 * The season as bars, one per week, scaled to the player's own best week.
 *
 * Scaled to the player rather than to a league-wide maximum on purpose: this chart is
 * read to compare a player against himself, and a shared scale flattens every kicker in
 * the league into an unreadable strip.
 */
@Composable
private fun GameLog(log: List<Pair<Int, Double>>, currentWeek: Int) {
    val best = log.maxOfOrNull { it.second }?.coerceAtLeast(1.0) ?: 1.0
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Canvas(Modifier.fillMaxWidth().height(88.dp)) {
            val gap = 3.dp.toPx()
            val barWidth = ((size.width - gap * (log.size - 1)) / log.size).coerceAtLeast(1f)
            log.forEachIndexed { index, (week, points) ->
                val height = (points / best * size.height).toFloat().coerceAtLeast(1f)
                val x = index * (barWidth + gap)
                drawRect(
                    color = if (week == currentWeek) Color.White else Dim,
                    topLeft = androidx.compose.ui.geometry.Offset(x, size.height - height),
                    size = androidx.compose.ui.geometry.Size(barWidth, height),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(
                "WK ${log.first().first}",
                style = MaterialTheme.typography.labelSmall,
                color = Faint,
                modifier = Modifier.weight(1f),
            )
            Text(
                "BEST ${pts(best)}",
                style = MaterialTheme.typography.labelSmall,
                color = Faint,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            Text(
                "WK ${log.last().first}",
                style = MaterialTheme.typography.labelSmall,
                color = Faint,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
    }
    Rule(Modifier.padding(top = 4.dp))
    Column(Modifier.padding(bottom = 24.dp)) {}
}
