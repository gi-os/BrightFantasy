package com.gios.brightfantasy.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gios.brightfantasy.data.Espn
import com.gios.brightfantasy.data.FantasyApi
import com.gios.brightfantasy.data.FantasyRepository
import com.gios.brightfantasy.data.FantasyRepository.Outcome
import com.gios.brightfantasy.data.Prefs
import com.gios.brightfantasy.data.Setup
import com.gios.brightfantasy.model.DraftPick
import com.gios.brightfantasy.model.FreeAgent
import com.gios.brightfantasy.model.LeagueMove
import com.gios.brightfantasy.model.LeagueState
import com.gios.brightfantasy.model.Matchup
import com.gios.brightfantasy.model.Roster
import com.gios.brightfantasy.model.RosterSlot
import com.gios.brightfantasy.report.Trouble
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One view model for the whole app.
 *
 * Split screens would each need the league, the week and the team id, and keeping three
 * copies of "which week are we looking at" in sync across five tabs is the bug this
 * avoids. Every screen reads the same flows; nothing below fetches for itself.
 */
class FantasyViewModel(app: Application) : AndroidViewModel(app) {

    val prefs = Prefs(app)
    private val api = FantasyApi { prefs.credentials }
    private val repo = FantasyRepository(api, prefs, app.cacheDir)

    // ------------------------------------------------------------------ state

    private val _league = MutableStateFlow<LeagueState?>(null)
    val league: StateFlow<LeagueState?> = _league.asStateFlow()

    private val _week = MutableStateFlow(0)
    val week: StateFlow<Int> = _week.asStateFlow()

    private val _matchup = MutableStateFlow<FantasyRepository.MatchupDetail?>(null)
    val matchup: StateFlow<FantasyRepository.MatchupDetail?> = _matchup.asStateFlow()

    private val _scoreboard = MutableStateFlow<List<Matchup>>(emptyList())
    val scoreboard: StateFlow<List<Matchup>> = _scoreboard.asStateFlow()

    private val _roster = MutableStateFlow<Roster?>(null)
    val roster: StateFlow<Roster?> = _roster.asStateFlow()

    private val _agents = MutableStateFlow<List<FreeAgent>>(emptyList())
    val agents: StateFlow<List<FreeAgent>> = _agents.asStateFlow()

    private val _activity = MutableStateFlow<List<LeagueMove>>(emptyList())
    val activity: StateFlow<List<LeagueMove>> = _activity.asStateFlow()

    private val _draft = MutableStateFlow<List<DraftPick>>(emptyList())
    val draft: StateFlow<List<DraftPick>> = _draft.asStateFlow()

    private val _draftNames = MutableStateFlow<Map<Int, String>>(emptyMap())
    val draftNames: StateFlow<Map<Int, String>> = _draftNames.asStateFlow()

    private val _playerLog = MutableStateFlow<List<Pair<Int, Double>>>(emptyList())
    val playerLog: StateFlow<List<Pair<Int, Double>>> = _playerLog.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** The one line that tells you the screen is wrong, and why. Null when it isn't. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Set when ESPN refused the credentials, so the UI can offer setup rather than retry. */
    private val _needsLogin = MutableStateFlow(false)
    val needsLogin: StateFlow<Boolean> = _needsLogin.asStateFlow()

    /** Confirmation of something that worked, shown briefly and then dropped. */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    val configured: Boolean get() = prefs.configured

    fun clearError() {
        _error.value = null
    }

    fun clearNotice() {
        _notice.value = null
    }

    // ------------------------------------------------------------------ loading

    /**
     * The whole app's opening move: league, teams, schedule, then this week's matchup.
     *
     * The week is only defaulted the first time. Once someone has stepped back to look at
     * week 3, a refresh should refresh week 3 — a refresh that silently jumps to today is
     * the kind of thing that reads as the app losing your place.
     */
    fun refresh() {
        if (!prefs.configured) return
        launch {
            when (val result = repo.loadLeague()) {
                is Outcome.Ok -> {
                    val state = result.value
                    _league.value = state
                    _needsLogin.value = false
                    if (_week.value == 0) {
                        _week.value = defaultWeek(state)
                    }
                    ensureTeamId(state)
                    loadWeekData()
                }
                is Outcome.Failed -> fail(result)
            }
        }
    }

    /**
     * Which week to open on.
     *
     * `scoringPeriodId` runs ahead of the schedule once the fantasy season ends — a league
     * whose final week was 17 still reports period 19 in January — so it is clamped to a
     * week the schedule actually has. Before a season starts it is 0, which would ask for
     * week 0 and get an empty scoreboard.
     */
    private fun defaultWeek(state: LeagueState): Int {
        val played = state.weeksPlayed()
        val wanted = maxOf(state.info.scoringPeriodId, state.info.matchupPeriodId, 1)
        if (played.isEmpty()) return wanted
        return wanted.coerceIn(played.first(), played.last())
    }

    /**
     * Work out which team is yours from the SWID, once.
     *
     * A team's `owners` are member GUIDs in exactly the format the SWID cookie carries, so
     * the app never has to ask. Case-insensitively, because ESPN is inconsistent about it
     * between the cookie and the payload — matching exactly finds nothing and leaves the
     * app quietly showing someone else's team.
     */
    private fun ensureTeamId(state: LeagueState) {
        if (prefs.teamId != 0 && state.team(prefs.teamId) != null) return
        val swid = prefs.swid.trim()
        val mine = state.teams.firstOrNull { team ->
            team.owners.any { it.equals(swid, ignoreCase = true) }
        }
        prefs.teamId = mine?.id ?: state.teams.firstOrNull()?.id ?: 0
    }

    fun setWeek(week: Int) {
        if (week == _week.value) return
        _week.value = week
        loadWeekData()
    }

    fun stepWeek(delta: Int) {
        val state = _league.value ?: return
        val played = state.weeksPlayed()
        val bounds = if (played.isEmpty()) 1..state.info.finalScoringPeriod
        else played.first()..played.last()
        setWeek((_week.value + delta).coerceIn(bounds))
    }

    private fun loadWeekData() {
        val teamId = prefs.teamId
        val week = _week.value
        if (teamId == 0 || week == 0) return
        launch {
            when (val result = repo.loadMatchup(teamId, week)) {
                is Outcome.Ok -> {
                    _matchup.value = result.value
                    // The roster tab and the matchup share one fetch; loading the same
                    // team's lineup twice for two tabs is a request nobody asked for.
                    _roster.value = result.value.myRoster
                }
                is Outcome.Failed -> fail(result)
            }
            when (val result = repo.loadWeek(week)) {
                is Outcome.Ok -> _scoreboard.value = result.value
                is Outcome.Failed -> Unit
            }
        }
    }

    /** Somebody else's team, for the league screen. */
    fun loadOtherRoster(teamId: Int, onLoaded: (Roster?) -> Unit) {
        launch {
            val result = repo.loadRoster(teamId, _week.value)
            onLoaded((result as? Outcome.Ok)?.value)
        }
    }

    // ------------------------------------------------------------------ the wire

    fun loadAgents(
        slotIds: List<Int> = Espn.SEARCHABLE_SLOTS,
        onWaivers: Boolean? = null,
    ) {
        launch {
            val sort = if (prefs.waiversByProjection) Espn.PlayerSort.PROJECTION
            else Espn.PlayerSort.OWNERSHIP
            when (
                val result = repo.freeAgents(
                    week = _week.value.coerceAtLeast(1),
                    slotIds = slotIds,
                    onWaivers = onWaivers,
                    sort = sort,
                )
            ) {
                is Outcome.Ok -> _agents.value = result.value
                is Outcome.Failed -> fail(result)
            }
        }
    }

    fun loadActivity() {
        launch {
            when (val result = repo.activity()) {
                is Outcome.Ok -> _activity.value = result.value
                is Outcome.Failed -> fail(result)
            }
        }
    }

    fun loadDraft() {
        launch {
            when (val result = repo.draft()) {
                is Outcome.Ok -> {
                    _draft.value = result.value
                    // The board is two hundred player ids and nothing else; the names are
                    // a second request, cached for the season after the first draft view.
                    _draftNames.value = withContext(Dispatchers.IO) {
                        repo.playerNames(result.value.map { it.playerId })
                    }
                }
                is Outcome.Failed -> fail(result)
            }
        }
    }

    fun loadPlayerLog(playerId: Int) {
        _playerLog.value = emptyList()
        launch {
            when (val result = repo.playerLog(playerId)) {
                is Outcome.Ok -> _playerLog.value = result.value
                is Outcome.Failed -> Unit
            }
        }
    }

    // ------------------------------------------------------------------ writes

    /**
     * Swap two players' slots.
     *
     * Refused up front rather than by ESPN when the move is illegal, because ESPN's
     * refusal arrives as a 400 with a message about lineup slot ids and the honest version
     * — "Josh Allen can't play flex" — is available here for free.
     */
    fun swap(a: RosterSlot, b: RosterSlot) {
        val problem = swapProblem(a, b)
        if (problem != null) {
            _error.value = problem
            return
        }
        val teamId = prefs.teamId
        val week = _week.value
        launch {
            when (val result = repo.setLineup(teamId, week, repo.swapMoves(a, b))) {
                is Outcome.Ok -> {
                    _notice.value = "Lineup saved"
                    loadWeekData()
                }
                is Outcome.Failed -> {
                    fail(result)
                    // A lineup that failed to save is exactly the kind of quiet failure
                    // that never gets reported, so the app offers to file it.
                    Trouble.record("save the lineup", result.message)
                }
            }
        }
    }

    /** Null when the swap is legal, otherwise the reason in plain words. */
    fun swapProblem(a: RosterSlot, b: RosterSlot): String? = when {
        a.playerId == b.playerId -> "Pick two different players."
        a.lineupLocked || b.lineupLocked ->
            "${if (a.lineupLocked) a.name else b.name} is locked — that game has started."
        // Bench and IR slots take anyone, so only a move *into* a starting slot is
        // checked against eligibility.
        Espn.isStarting(b.slotId) && b.slotId !in a.eligibleSlots ->
            "${a.name} can't play ${Espn.slotName(b.slotId)}."
        Espn.isStarting(a.slotId) && a.slotId !in b.eligibleSlots ->
            "${b.name} can't play ${Espn.slotName(a.slotId)}."
        else -> null
    }

    fun addDrop(add: FreeAgent, drop: RosterSlot?, bid: Int = 0) {
        val teamId = prefs.teamId
        val week = _week.value.coerceAtLeast(1)
        launch {
            when (
                val result = repo.addDrop(
                    teamId = teamId,
                    week = week,
                    addPlayerId = add.playerId,
                    dropPlayerId = drop?.playerId,
                    onWaivers = add.onWaivers,
                    bid = bid,
                )
            ) {
                is Outcome.Ok -> {
                    _notice.value = if (add.onWaivers) "Claim submitted" else "${add.name} added"
                    loadWeekData()
                }
                is Outcome.Failed -> {
                    fail(result)
                    Trouble.record("add ${add.name}", result.message)
                }
            }
        }
    }

    // ------------------------------------------------------------------ setup

    /** Returns what was recognised, so the setup screen can say what it got. */
    fun applySetup(scanned: String): Setup.Payload? {
        val payload = Setup.parse(scanned) ?: return null
        prefs.apply(payload)
        // A new league is a new team; keeping the old id would show team 4 of a league
        // that may not have one.
        if (payload.leagueId != null && payload.teamId == null) prefs.teamId = 0
        _week.value = 0
        _needsLogin.value = false
        _error.value = null
        refresh()
        return payload
    }

    fun signOut() {
        prefs.signOut()
        _league.value = null
        _matchup.value = null
        _roster.value = null
        _scoreboard.value = emptyList()
        _agents.value = emptyList()
        _activity.value = emptyList()
        _draft.value = emptyList()
        _week.value = 0
    }

    // ------------------------------------------------------------------ plumbing

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            // Every repository call is a blocking OkHttp round trip plus a JSON parse of
            // up to a hundred kilobytes. Neither belongs on the frame thread.
            withContext(Dispatchers.IO) { block() }
            _busy.value = false
        }
    }

    private fun fail(result: Outcome.Failed) {
        _error.value = result.message
        if (result.needsLogin) _needsLogin.value = true
    }
}
