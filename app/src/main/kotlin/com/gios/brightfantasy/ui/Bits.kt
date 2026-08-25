package com.gios.brightfantasy.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gios.brightfantasy.data.Espn
import com.gios.brightfantasy.model.FantasyTeam
import com.gios.brightfantasy.model.RosterSlot
import com.gios.brightfantasy.ui.theme.Dim
import com.gios.brightfantasy.ui.theme.Faint
import com.gios.brightfantasy.ui.theme.RuleGrey
import java.util.Locale

/**
 * The pieces every fantasy screen is built from.
 *
 * Fantasy football is a wall of numbers, and a 3.9" greyscale panel is the worst possible
 * surface for one. So the rules here are strict: one number per row is large, everything
 * supporting it is grey and small, and nothing is coloured because colour does not survive
 * the panel's own greyscale matrix.
 */

/** Points, at the precision ESPN scores at and no more. */
fun pts(value: Double): String = String.format(Locale.US, "%.1f", value)

fun signed(value: Double): String =
    (if (value >= 0) "+" else "") + String.format(Locale.US, "%.1f", value)

/**
 * A team's mark.
 *
 * Initials in a box rather than the crest ESPN serves. Its default team logos are SVG,
 * which `BitmapFactory` cannot decode at all, and a custom one is somebody's meme JPEG
 * rendered through a greyscale matrix at 24dp — unreadable either way. Two letters are
 * legible at any size and cost no request.
 */
@Composable
fun TeamMark(team: FantasyTeam?, size: Int = 30, selected: Boolean = false) {
    val label = (team?.abbrev ?: "?").take(3).uppercase()
    Box(
        Modifier
            .size(size.dp)
            .background(if (selected) Color.White else Color.Black)
            .border(1.dp, if (selected) Color.White else RuleGrey),
        Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.5.sp),
            color = if (selected) Color.Black else Color.White,
            maxLines = 1,
        )
    }
}

/**
 * The head-to-head header: two teams, two scores, and the gap between them.
 *
 * The gap is the point of the screen. Everyone can subtract, but on a Sunday afternoon
 * the number people actually want is "am I up, and by how much" — putting it between the
 * two scores means the answer is where the eye already is.
 */
@Composable
fun ScoreHead(
    left: FantasyTeam?,
    leftPoints: Double,
    right: FantasyTeam?,
    rightPoints: Double,
    leftNote: String? = null,
    rightNote: String? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        SideOfHead(left, leftPoints, leftNote, TextAlign.Start, Modifier.weight(1f))
        Column(
            Modifier.width(64.dp).padding(top = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val margin = leftPoints - rightPoints
            Text(
                if (margin == 0.0) "TIED" else signed(margin),
                style = MaterialTheme.typography.bodyMedium,
                color = Dim,
                maxLines = 1,
            )
        }
        SideOfHead(right, rightPoints, rightNote, TextAlign.End, Modifier.weight(1f))
    }
}

@Composable
private fun SideOfHead(
    team: FantasyTeam?,
    points: Double,
    note: String?,
    align: TextAlign,
    modifier: Modifier,
) {
    Column(
        modifier,
        horizontalAlignment = if (align == TextAlign.Start) Alignment.Start else Alignment.End,
    ) {
        Text(
            team?.name ?: "—",
            style = MaterialTheme.typography.bodyMedium,
            color = Dim,
            textAlign = align,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            pts(points),
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
            maxLines = 1,
        )
        if (note != null) {
            Text(
                note,
                style = MaterialTheme.typography.labelSmall,
                color = Faint,
                textAlign = align,
                maxLines = 1,
            )
        }
    }
}

/**
 * Week 7 of 17, with a step either side.
 *
 * The arrows are wide targets rather than tight ones: this is the control people press
 * most and the panel is small enough that a 24dp icon is a miss half the time.
 */
@Composable
fun WeekBar(
    week: Int,
    label: String,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onStep: (Int) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepArrow("‹", canGoBack) { onStep(-1) }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = Dim,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        StepArrow("›", canGoForward) { onStep(1) }
    }
}

@Composable
private fun StepArrow(glyph: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .width(56.dp)
            .height(40.dp)
            .let { if (enabled) it.clickable(onClick = onClick) else it },
        Alignment.Center,
    ) {
        Text(
            glyph,
            style = MaterialTheme.typography.titleLarge,
            color = if (enabled) Color.White else Faint,
        )
    }
}

/**
 * One player, one line.
 *
 * The layout is fixed-width on both edges so a column of them lines up: slot on the left
 * at a constant width, points on the right at a constant width, name taking whatever is
 * left. Ragged columns are what makes a stats screen unreadable at this size.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlayerRow(
    slot: RosterSlot,
    showProjection: Boolean,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    // Selection inverts nothing here — a fully inverted row in a list of rows reads as a
    // rendering fault on this panel. A lifted background is enough at arm's length.
    val background = if (selected) Color(0xFF232323) else Color.Black
    Row(
        Modifier
            .fillMaxWidth()
            .background(background)
            .let { m ->
                when {
                    // `combinedClickable` adds a hold delay to the ordinary tap, so it is
                    // only used where a long press is actually wanted.
                    onLongClick != null -> m.combinedClickable(
                        onClick = onClick ?: {},
                        onLongClick = onLongClick,
                    )
                    onClick != null -> m.clickable(onClick = onClick)
                    else -> m
                }
            }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            slot.slotName,
            style = MaterialTheme.typography.labelSmall,
            color = if (slot.starting) Color.White else Faint,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.width(40.dp),
        )
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    slot.name.ifBlank { "—" },
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                slot.injuryTag?.let {
                    Text(
                        " $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = Dim,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
            }
            Text(
                playerSubtitle(slot),
                style = MaterialTheme.typography.bodyMedium,
                color = Dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(56.dp)) {
            Text(
                pts(slot.points),
                style = MaterialTheme.typography.bodyLarge,
                // A player who has finished is a settled number; one still playing is
                // provisional, and dimming it is the only way to say so without a word.
                color = if (slot.yetToPlay) Dim else Color.White,
                maxLines = 1,
            )
            if (showProjection) {
                Text(
                    pts(slot.projected),
                    style = MaterialTheme.typography.labelSmall,
                    color = Faint,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * `position • team vs opponent`, or the reason there is no game.
 *
 * A bye and an unplayed game look identical in the payload — both are zero points — so
 * saying which is the difference between "he blanked" and "he wasn't playing".
 */
private fun playerSubtitle(slot: RosterSlot): String {
    val base = "${slot.position} · ${slot.proTeam}"
    if (slot.proTeamId == 0) return "Free agent"
    if (slot.kickoffMillis == 0L) return "$base · BYE"
    val opponent = Espn.proTeam(slot.opponentProTeamId)
    return "$base vs $opponent"
}

/** A label above a block, in the SDK's own small-caps idiom. */
@Composable
fun Caption(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = Faint,
        modifier = modifier,
        maxLines = 1,
    )
}

/** A row of two figures with their labels, for the summary strips. */
@Composable
fun StatStrip(items: List<Pair<String, String>>) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        items.forEach { (label, value) ->
            Column(horizontalAlignment = Alignment.Start) {
                Caption(label)
                Text(
                    value,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                    maxLines = 1,
                )
            }
        }
    }
}
