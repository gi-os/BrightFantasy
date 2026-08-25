package com.gios.brightfantasy.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import com.gios.brightfantasy.hw.WheelScroll
import com.gios.brightfantasy.model.DraftPick
import com.gios.brightfantasy.model.FantasyTeam
import com.gios.brightfantasy.model.LeagueMove
import com.gios.brightfantasy.model.LeagueState
import com.gios.brightfantasy.model.Matchup
import com.gios.brightfantasy.ui.theme.Dim
import com.gios.brightfantasy.ui.theme.Faint
import com.gios.brightfantasy.util.Fmt
import java.time.ZoneId

/** The five things about the league that are not your own team. */
enum class LeaguePage(val title: String) {
    SCOREBOARD("Scores"),
    STANDINGS("Standings"),
    POWER("Power"),
    ACTIVITY("Moves"),
    DRAFT("Draft"),
}

@Composable
fun LeagueScreen(
    page: LeaguePage,
    league: LeagueState?,
    myTeamId: Int,
    week: Int,
    scoreboard: List<Matchup>,
    activity: List<LeagueMove>,
    draft: List<DraftPick>,
    draftNames: Map<Int, String>,
    onStepWeek: (Int) -> Unit,
    onTeam: (FantasyTeam) -> Unit,
) {
    if (league == null) {
        EmptyState("Nothing loaded yet.")
        return
    }
    val listState = rememberLazyListState()
    WheelScroll(listState)

    LazyColumn(Modifier.fillMaxSize(), listState) {
        when (page) {
            LeaguePage.SCOREBOARD -> {
                val played = league.weeksPlayed()
                item {
                    WeekBar(
                        week = week,
                        label = "WEEK $week",
                        canGoBack = played.isEmpty() || week > played.first(),
                        canGoForward = played.isEmpty() || week < played.last(),
                        onStep = onStepWeek,
                    )
                    Rule()
                }
                if (scoreboard.isEmpty()) {
                    item { EmptyState("No games this week.") }
                }
                items(scoreboard.size) { i ->
                    ScoreboardRow(scoreboard[i], league, myTeamId, onTeam)
                }
            }

            LeaguePage.STANDINGS -> {
                item { StandingsHeader() }
                val rows = league.standings()
                items(rows.size) { i ->
                    StandingsRow(
                        rank = i + 1,
                        team = rows[i],
                        mine = rows[i].id == myTeamId,
                        // The playoff cut is the only line in a standings table anyone
                        // looks for, so it is drawn rather than counted.
                        cutBelow = i + 1 == league.info.playoffTeams,
                        onClick = { onTeam(rows[i]) },
                    )
                }
            }

            LeaguePage.POWER -> {
                item {
                    Caption(
                        "Points scored, weighted two to one against record",
                        Modifier.padding(16.dp),
                    )
                    Rule()
                }
                val rows = league.power()
                items(rows.size) { i ->
                    val (team, score) = rows[i]
                    MenuRow(
                        label = "${i + 1}. ${team.name}",
                        sub = "${team.record} · ${pts(team.pointsFor)} PF",
                        detail = pts(score),
                        dim = team.id != myTeamId,
                        onClick = { onTeam(team) },
                    )
                }
            }

            LeaguePage.ACTIVITY -> {
                if (activity.isEmpty()) {
                    item {
                        EmptyState(
                            "No moves yet.\n\nOlder leagues have no activity feed at all — " +
                                "ESPN answers 404 rather than an empty one.",
                        )
                    }
                }
                items(activity.size) { i ->
                    MoveRow(activity[i], league)
                }
            }

            LeaguePage.DRAFT -> {
                if (draft.isEmpty()) {
                    item { EmptyState("This league hasn't drafted yet.") }
                }
                items(draft.size) { i ->
                    val pick = draft[i]
                    Column(Modifier.fillMaxWidth()) {
                        // The round header rides on the first pick of the round rather
                        // than being its own item, so the list stays one item per pick and
                        // scroll position survives the names arriving.
                        if (pick.round != draft.getOrNull(i - 1)?.round) {
                            SectionHeader("Round ${pick.round}")
                        }
                        MenuRow(
                            label = draftNames[pick.playerId] ?: "Player #${pick.playerId}",
                            sub = league.team(pick.teamId)?.name ?: "Team ${pick.teamId}",
                            detail = if (pick.bidAmount > 0) {
                                "$${pick.bidAmount}"
                            } else {
                                "${pick.overall}"
                            },
                            dim = pick.teamId != myTeamId,
                        )
                    }
                }
            }
        }
        item { Column(Modifier.padding(bottom = 24.dp)) {} }
    }
}

@Composable
private fun ScoreboardRow(
    matchup: Matchup,
    league: LeagueState,
    myTeamId: Int,
    onTeam: (FantasyTeam) -> Unit,
) {
    val home = league.team(matchup.home.teamId)
    val away = matchup.away?.let { league.team(it.teamId) }
    val mine = matchup.involves(myTeamId)
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        SideLine(
            team = away,
            points = matchup.away?.points ?: 0.0,
            winner = matchup.winner == "AWAY",
            emphasised = mine,
            onClick = { away?.let(onTeam) },
        )
        SideLine(
            team = home,
            points = matchup.home.points,
            winner = matchup.winner == "HOME",
            emphasised = mine,
            onClick = { home?.let(onTeam) },
        )
        if (matchup.isBye) {
            Text(
                "BYE",
                style = MaterialTheme.typography.labelSmall,
                color = Faint,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
        Rule(Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun SideLine(
    team: FantasyTeam?,
    points: Double,
    winner: Boolean,
    emphasised: Boolean,
    onClick: () -> Unit,
) {
    if (team == null) return
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            team.name,
            style = MaterialTheme.typography.bodyLarge,
            // The loser goes grey. On a matte greyscale panel that is the only way to
            // mark a result that does not look like a rendering artefact.
            color = if (winner || !emphasised) Color.White else Dim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            team.record,
            style = MaterialTheme.typography.bodyMedium,
            color = Faint,
            modifier = Modifier.padding(end = 10.dp),
        )
        Text(
            pts(points),
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
            textAlign = TextAlign.End,
            modifier = Modifier.width(56.dp),
        )
    }
}

@Composable
private fun StandingsHeader() {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Caption("Team", Modifier.weight(1f))
        Caption("W-L", Modifier.width(52.dp))
        Caption("PF", Modifier.width(56.dp))
    }
    Rule()
}

@Composable
private fun StandingsRow(
    rank: Int,
    team: FantasyTeam,
    mine: Boolean,
    cutBelow: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$rank",
            style = MaterialTheme.typography.bodyMedium,
            color = Faint,
            modifier = Modifier.width(22.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                team.name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (mine) Color.White else Dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${team.streak} · ${pts(team.pointsAgainst)} PA",
                style = MaterialTheme.typography.labelSmall,
                color = Faint,
                maxLines = 1,
            )
        }
        Text(
            team.record,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
            modifier = Modifier.width(52.dp),
        )
        Text(
            pts(team.pointsFor),
            style = MaterialTheme.typography.bodyMedium,
            color = Dim,
            textAlign = TextAlign.End,
            modifier = Modifier.width(56.dp),
        )
    }
    // The playoff line, drawn brighter than an ordinary divider so it reads as a cut
    // rather than as the next row starting.
    if (cutBelow) {
        Rule(Modifier.padding(vertical = 2.dp))
        Text(
            "PLAYOFF LINE",
            style = MaterialTheme.typography.labelSmall,
            color = Faint,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        )
    }
    Rule()
}

@Composable
private fun MoveRow(move: LeagueMove, league: LeagueState) {
    val team = league.team(move.teamId)?.name ?: "Team ${move.teamId}"
    val what = buildString {
        if (move.added.isNotEmpty()) append("+ ").append(move.added.joinToString(", "))
        if (move.dropped.isNotEmpty()) {
            if (isNotEmpty()) append("   ")
            append("− ").append(move.dropped.joinToString(", "))
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                move.label,
                style = MaterialTheme.typography.labelSmall,
                color = Faint,
                modifier = Modifier.weight(1f),
            )
            Text(
                Fmt.dayDate(move.date, ZoneId.systemDefault()),
                style = MaterialTheme.typography.labelSmall,
                color = Faint,
            )
        }
        Text(what, style = MaterialTheme.typography.bodyLarge, color = Color.White)
        Text(
            if (move.bid > 0) "$team · $${move.bid}" else team,
            style = MaterialTheme.typography.bodyMedium,
            color = Dim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    Rule()
}
