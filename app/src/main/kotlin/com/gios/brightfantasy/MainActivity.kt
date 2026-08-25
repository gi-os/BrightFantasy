package com.gios.brightfantasy

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gios.brightfantasy.hw.LightKey
import com.gios.brightfantasy.hw.LightKeys
import com.gios.brightfantasy.hw.LocalWheelBus
import com.gios.brightfantasy.hw.WheelBus
import com.gios.brightfantasy.model.FreeAgent
import com.gios.brightfantasy.model.Roster
import com.gios.brightfantasy.model.RosterSlot
import com.gios.brightfantasy.report.CrashLog
import com.gios.brightfantasy.report.ReportContext
import com.gios.brightfantasy.report.ReportOverlay
import com.gios.brightfantasy.ui.BarItem
import com.gios.brightfantasy.ui.Chip
import com.gios.brightfantasy.ui.EmptyState
import com.gios.brightfantasy.ui.FantasyViewModel
import com.gios.brightfantasy.ui.LeaguePage
import com.gios.brightfantasy.ui.LeagueScreen
import com.gios.brightfantasy.ui.LightBottomBar
import com.gios.brightfantasy.ui.LightTopBar
import com.gios.brightfantasy.ui.MatchupScreen
import com.gios.brightfantasy.ui.MenuRow
import com.gios.brightfantasy.ui.PlayerScreen
import com.gios.brightfantasy.ui.ReadOnlyRoster
import com.gios.brightfantasy.ui.RosterScreen
import com.gios.brightfantasy.ui.Rule
import com.gios.brightfantasy.ui.SectionHeader
import com.gios.brightfantasy.ui.SettingsScreen
import com.gios.brightfantasy.ui.SetupScreen
import com.gios.brightfantasy.ui.WireScreen
import com.gios.brightfantasy.ui.DropPicker
import com.gios.brightfantasy.ui.theme.BrightFantasyTheme
import com.gios.brightfantasy.ui.theme.Dim
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    /** Wheel notches on their way to whichever screen is up. */
    private val wheel = WheelBus()

    /**
     * Every hardware key arrives here first — `DecorView` hands the event to the window
     * callback before it walks the view hierarchy — so a turn of the wheel reaches the
     * list even when something else holds focus. Both halves of the pair are consumed:
     * one notch is a complete DOWN+UP.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (LightKeys.of(event)) {
            LightKey.WheelUp -> {
                if (event.action == KeyEvent.ACTION_DOWN) wheel.send(1)
                return true
            }
            LightKey.WheelDown -> {
                if (event.action == KeyEvent.ACTION_DOWN) wheel.send(-1)
                return true
            }
            else -> Unit
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Before anything else can throw. The handler chains onto whatever is already
        // installed and only writes a file, so it is safe this early.
        CrashLog.install(this)

        setContent {
            BrightFantasyTheme {
                Surface(Modifier.fillMaxSize(), color = Color.Black) {
                    CompositionLocalProvider(LocalWheelBus provides wheel) {
                        App()
                        // Shake to report, the crash offer on next launch, and the app's
                        // own noticed failures. A sibling, not a wrapper.
                        ReportOverlay()
                    }
                }
            }
        }
    }
}

private const val TAB_MATCHUP = 0
private const val TAB_ROSTER = 1
private const val TAB_LEAGUE = 2
private const val TAB_WIRE = 3
private const val TAB_SETTINGS = 4

/** Everything stacked on top of a tab. Back pops one level at a time. */
private sealed interface Overlay {
    data class Player(val slot: RosterSlot) : Overlay
    data class Team(val teamId: Int, val name: String) : Overlay
    data class Drop(val add: FreeAgent) : Overlay
    data object Setup : Overlay
    data object PickTeam : Overlay
}

@Composable
private fun App() {
    val vm: FantasyViewModel = viewModel()
    val league by vm.league.collectAsState()
    val matchup by vm.matchup.collectAsState()
    val roster by vm.roster.collectAsState()
    val scoreboard by vm.scoreboard.collectAsState()
    val agents by vm.agents.collectAsState()
    val activity by vm.activity.collectAsState()
    val draft by vm.draft.collectAsState()
    val draftNames by vm.draftNames.collectAsState()
    val playerLog by vm.playerLog.collectAsState()
    val week by vm.week.collectAsState()
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    val notice by vm.notice.collectAsState()
    val needsLogin by vm.needsLogin.collectAsState()

    var tab by remember { mutableStateOf(TAB_MATCHUP) }
    var leaguePage by remember { mutableStateOf(LeaguePage.SCOREBOARD) }
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    var otherRoster by remember { mutableStateOf<Roster?>(null) }

    // An unconfigured app opens on setup rather than on an empty matchup, because there
    // is nothing else it could usefully show and "no matchup this week" reads as broken.
    LaunchedEffect(Unit) {
        if (!vm.configured) overlay = Overlay.Setup else vm.refresh()
    }

    // ESPN refusing the credentials is not a thing to retry — it needs a new code — so it
    // takes you straight to the one screen that can fix it.
    LaunchedEffect(needsLogin) {
        if (needsLogin) overlay = Overlay.Setup
    }

    LaunchedEffect(notice) {
        if (notice != null) {
            delay(2_500)
            vm.clearNotice()
        }
    }

    // The crash report wants to know where you were, and it is read from a dying thread
    // that cannot see the composition — so it is written down as it changes.
    LaunchedEffect(tab, leaguePage, overlay) {
        ReportContext.screen = when {
            overlay is Overlay.Setup -> "setup"
            overlay is Overlay.Player -> "player"
            overlay is Overlay.Team -> "team"
            overlay is Overlay.Drop -> "drop"
            tab == TAB_MATCHUP -> "matchup"
            tab == TAB_ROSTER -> "roster"
            tab == TAB_LEAGUE -> "league:${leaguePage.name.lowercase()}"
            tab == TAB_WIRE -> "wire"
            else -> "settings"
        }
    }

    // The league tab's pages each need their own fetch, and only when opened.
    LaunchedEffect(tab, leaguePage) {
        when {
            tab == TAB_LEAGUE && leaguePage == LeaguePage.ACTIVITY -> vm.loadActivity()
            tab == TAB_LEAGUE && leaguePage == LeaguePage.DRAFT -> vm.loadDraft()
        }
    }

    // LightOS supplies the back gesture and its own screens expect it to unwind rather
    // than leave the app, so it is handled wherever there is a level to pop.
    BackHandler(enabled = overlay != null && overlay != Overlay.Setup) {
        overlay = null
        otherRoster = null
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(
            overlay = overlay,
            tab = tab,
            leaguePage = leaguePage,
            onBack = {
                overlay = null
                otherRoster = null
            },
            onRefresh = { vm.refresh() },
        )
        Rule()

        // One line, above everything, that says whether the screen can be trusted.
        val banner = error ?: notice ?: if (busy && league == null) "Loading…" else null
        banner?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = if (error != null) Color.White else Dim,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Rule()
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val open = overlay) {
                is Overlay.Setup -> SetupScreen(
                    hasLeague = vm.prefs.configured,
                    signedIn = vm.prefs.signedIn,
                    leagueId = vm.prefs.leagueId,
                    onScanned = { text ->
                        vm.applySetup(text) != null
                    },
                    onDone = { overlay = null },
                )

                is Overlay.Player -> PlayerScreen(open.slot, playerLog, week)

                is Overlay.Team -> ReadOnlyRoster(
                    roster = otherRoster,
                    showProjections = vm.prefs.showProjections,
                    onPlayer = { slot ->
                        vm.loadPlayerLog(slot.playerId)
                        overlay = Overlay.Player(slot)
                    },
                )

                is Overlay.Drop -> DropPicker(
                    add = open.add,
                    roster = roster,
                    onPick = { drop ->
                        vm.addDrop(open.add, drop)
                        overlay = null
                    },
                    onCancel = { overlay = null },
                )

                is Overlay.PickTeam -> {
                    val teams = league?.teams.orEmpty()
                    if (teams.isEmpty()) {
                        EmptyState("No teams loaded.")
                    } else {
                        Column(Modifier.fillMaxSize()) {
                            SectionHeader("Which team is yours?")
                            teams.forEach { team ->
                                MenuRow(
                                    label = team.name,
                                    sub = team.record,
                                    dim = team.id != vm.prefs.teamId,
                                    onClick = {
                                        vm.prefs.teamId = team.id
                                        overlay = null
                                        vm.refresh()
                                    },
                                )
                            }
                        }
                    }
                }

                null -> when (tab) {
                    TAB_MATCHUP -> MatchupScreen(
                        detail = matchup,
                        league = league,
                        week = week,
                        showProjections = vm.prefs.showProjections,
                        onStepWeek = { vm.stepWeek(it) },
                        onPlayer = {
                            vm.loadPlayerLog(it.playerId)
                            overlay = Overlay.Player(it)
                        },
                    )

                    TAB_ROSTER -> RosterScreen(
                        roster = roster,
                        league = league,
                        week = week,
                        showProjections = vm.prefs.showProjections,
                        // A past week cannot be changed, and offering the taps that would
                        // try is worse than not offering them.
                        editable = week >= (league?.info?.scoringPeriodId ?: week),
                        onStepWeek = { vm.stepWeek(it) },
                        onSwap = { a, b -> vm.swap(a, b) },
                        swapProblem = { a, b -> vm.swapProblem(a, b) },
                        onPlayer = {
                            vm.loadPlayerLog(it.playerId)
                            overlay = Overlay.Player(it)
                        },
                    )

                    TAB_LEAGUE -> Column(Modifier.fillMaxSize()) {
                        LeaguePager(leaguePage) { leaguePage = it }
                        Rule()
                        LeagueScreen(
                            page = leaguePage,
                            league = league,
                            myTeamId = vm.prefs.teamId,
                            week = week,
                            scoreboard = scoreboard,
                            activity = activity,
                            draft = draft,
                            draftNames = draftNames,
                            onStepWeek = { vm.stepWeek(it) },
                            onTeam = { team ->
                                otherRoster = null
                                overlay = Overlay.Team(team.id, team.name)
                                vm.loadOtherRoster(team.id) { otherRoster = it }
                            },
                        )
                    }

                    TAB_WIRE -> WireScreen(
                        agents = agents,
                        roster = roster,
                        byProjection = vm.prefs.waiversByProjection,
                        onLoad = { slots, waiversOnly -> vm.loadAgents(slots, waiversOnly) },
                        onToggleSort = {
                            vm.prefs.waiversByProjection = !vm.prefs.waiversByProjection
                            vm.loadAgents()
                        },
                        onAdd = { overlay = Overlay.Drop(it) },
                    )

                    else -> SettingsScreen(
                        prefs = vm.prefs,
                        league = league,
                        version = BuildConfig.VERSION_NAME,
                        onSetup = { overlay = Overlay.Setup },
                        onPickTeam = { overlay = Overlay.PickTeam },
                        onSignOut = {
                            vm.signOut()
                            overlay = Overlay.Setup
                        },
                    )
                }
            }
        }

        // Setup owns the whole screen: it is the one place where there is nowhere else to
        // go until it is finished.
        if (overlay !is Overlay.Setup) {
            Rule()
            LightBottomBar(
                listOf(
                    BarItem.Icon(
                        R.drawable.ic_star_white,
                        { tab = TAB_MATCHUP; overlay = null },
                        "Matchup",
                        selected = tab == TAB_MATCHUP,
                    ),
                    BarItem.Icon(
                        R.drawable.ic_list_white,
                        { tab = TAB_ROSTER; overlay = null },
                        "Roster",
                        selected = tab == TAB_ROSTER,
                    ),
                    BarItem.Icon(
                        R.drawable.ic_large_list_white,
                        { tab = TAB_LEAGUE; overlay = null },
                        "League",
                        selected = tab == TAB_LEAGUE,
                    ),
                    BarItem.Icon(
                        R.drawable.ic_search_white,
                        { tab = TAB_WIRE; overlay = null },
                        "Wire",
                        selected = tab == TAB_WIRE,
                    ),
                    BarItem.Icon(
                        R.drawable.ic_settings_white,
                        { tab = TAB_SETTINGS; overlay = null },
                        "Settings",
                        selected = tab == TAB_SETTINGS,
                    ),
                ),
            )
        }
    }
}

@Composable
private fun TopBar(
    overlay: Overlay?,
    tab: Int,
    leaguePage: LeaguePage,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
) {
    when (overlay) {
        is Overlay.Setup -> LightTopBar(title = "SETUP")
        is Overlay.Player -> LightTopBar(
            left = BarItem.Icon(R.drawable.ic_back_white, onBack, "Back"),
            title = "PLAYER",
        )
        is Overlay.Team -> LightTopBar(
            left = BarItem.Icon(R.drawable.ic_back_white, onBack, "Back"),
            title = overlay.name.uppercase(),
        )
        is Overlay.Drop -> LightTopBar(
            left = BarItem.Icon(R.drawable.ic_back_white, onBack, "Back"),
            title = "DROP",
        )
        is Overlay.PickTeam -> LightTopBar(
            left = BarItem.Icon(R.drawable.ic_back_white, onBack, "Back"),
            title = "MY TEAM",
        )
        null -> LightTopBar(
            title = when (tab) {
                TAB_MATCHUP -> "MATCHUP"
                TAB_ROSTER -> "MY TEAM"
                TAB_LEAGUE -> leaguePage.title.uppercase()
                TAB_WIRE -> "THE WIRE"
                else -> "SETTINGS"
            },
            right = BarItem.Icon(R.drawable.ic_refresh_white, onRefresh, "Refresh"),
        )
    }
}

/**
 * The five league pages, as a scrolling strip.
 *
 * Not five more entries in the bottom bar: the SDK caps that at five items and the app
 * already uses all five, and a second row of the same idiom would read as a second app
 * bar rather than as a filter on the one below it.
 */
@Composable
private fun LeaguePager(current: LeaguePage, onSelect: (LeaguePage) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LeaguePage.entries.forEach { page ->
            Chip(page.title.uppercase(), selected = page == current) { onSelect(page) }
        }
    }
}
