package com.gios.brightfantasy.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gios.brightfantasy.data.Espn
import com.gios.brightfantasy.data.Prefs
import com.gios.brightfantasy.hw.LightKeys
import com.gios.brightfantasy.hw.WheelScroll
import com.gios.brightfantasy.model.LeagueState
import com.gios.brightfantasy.ui.theme.Faint

@Composable
fun SettingsScreen(
    prefs: Prefs,
    league: LeagueState?,
    version: String,
    onSetup: () -> Unit,
    onPickTeam: () -> Unit,
    onSignOut: () -> Unit,
) {
    // SharedPreferences is not observable, so each toggle keeps the copy the screen is
    // drawing from. Writing through on change keeps the two in step without a flow apiece.
    var projections by remember { mutableStateOf(prefs.showProjections) }
    var waiverSort by remember { mutableStateOf(prefs.waiversByProjection) }
    var confirm by remember { mutableStateOf(prefs.confirmLineupChanges) }

    val scrollState = rememberScrollState()
    WheelScroll(scrollState)

    Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
        SectionHeader("League")
        MenuRow(
            label = league?.info?.name ?: "Not connected",
            sub = league?.let {
                "${it.info.season} · ${it.info.size} teams · ${it.info.scoringName}"
            },
            onClick = onSetup,
        )
        MenuRow(
            label = "My team",
            detail = league?.team(prefs.teamId)?.name ?: "—",
            onClick = onPickTeam,
        )
        MenuRow(
            label = "Season",
            detail = prefs.effectiveSeason().toString(),
            sub = if (prefs.season == 0) "Following the calendar" else "Pinned",
            onClick = {
                // Pinned to this season, or back to whatever the calendar says. Anything
                // finer than that is a year picker for a thing done once a year.
                prefs.season = if (prefs.season == 0) Espn.currentSeason() else 0
            },
        )
        Rule()

        SectionHeader("Display")
        Toggle("Show projections", projections) {
            projections = it
            prefs.showProjections = it
        }
        Toggle("Sort the wire by projection", waiverSort) {
            waiverSort = it
            prefs.waiversByProjection = it
        }
        Toggle("Confirm lineup changes", confirm) {
            confirm = it
            prefs.confirmLineupChanges = it
        }
        Rule()

        SectionHeader("Login")
        MenuRow(
            label = "Scan a new setup code",
            sub = "Cookies expire every few months",
            onClick = onSetup,
        )
        MenuRow(label = "Sign out", sub = "Forgets the league and the cookies", onClick = onSignOut)
        Rule()

        SectionHeader("About")
        MenuRow(label = "Version", detail = version)
        MenuRow(
            label = "Wheel scrolling",
            detail = if (LightKeys.wheelLabelsPresent()) "on" else "scancode fallback",
        )
        Text(
            ABOUT,
            style = MaterialTheme.typography.bodyMedium,
            color = Faint,
            modifier = Modifier.padding(16.dp),
        )
        Column(Modifier.padding(bottom = 24.dp)) {}
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    MenuRow(
        label = label,
        detail = if (value) "on" else "off",
        dim = !value,
        onClick = { onChange(!value) },
    )
}

private const val ABOUT =
    "Reads ESPN's fantasy API, which is undocumented and unofficial. Shake the phone to " +
        "report anything that looks wrong."
