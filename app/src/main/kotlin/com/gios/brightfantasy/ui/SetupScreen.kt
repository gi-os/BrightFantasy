package com.gios.brightfantasy.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.gios.brightfantasy.hw.WheelScroll
import com.gios.brightfantasy.ui.theme.Dim
import com.gios.brightfantasy.ui.theme.Faint

/**
 * Getting a 300-character session cookie onto a phone with no keyboard worth typing on.
 *
 * `espn_s2` is the whole problem. It is a long opaque blob that cannot be shortened,
 * guessed or derived, it is the only thing that reads a private league, and there is no
 * ESPN login flow an app can drive — no OAuth, no device code, nothing. So setup is: open
 * a page on a computer, paste two cookies into it, point the phone at the QR it draws.
 * The page renders the code locally in the browser and posts nothing anywhere.
 */
@Composable
fun SetupScreen(
    hasLeague: Boolean,
    signedIn: Boolean,
    leagueId: String,
    onScanned: (String) -> Boolean,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    var scanning by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        scanning = ok
        if (!ok) message = "Camera access is needed to read the code."
    }

    if (scanning && granted) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                QrScanner(Modifier.fillMaxSize()) { text ->
                    val recognised = onScanned(text)
                    scanning = false
                    message = if (recognised) {
                        "Scanned. Loading your league…"
                    } else {
                        "That code wasn't a Fantasy setup code."
                    }
                }
            }
            Rule()
            Text(
                "Point at the code on the setup page.",
                style = MaterialTheme.typography.bodyMedium,
                color = Dim,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
            Text(
                "Cancel",
                style = MaterialTheme.typography.bodyLarge,
                color = Faint,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { scanning = false }
                    .padding(16.dp),
            )
        }
        return
    }

    val scrollState = rememberScrollState()
    WheelScroll(scrollState)

    Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Connect your league",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
            )
            Text(
                "On a computer, open:",
                style = MaterialTheme.typography.bodyMedium,
                color = Dim,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                SETUP_PAGE,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                "Paste your ESPN league id and the two cookies it asks for, then scan the " +
                    "code it draws. The page runs entirely in your browser — nothing is " +
                    "uploaded anywhere.",
                style = MaterialTheme.typography.bodyMedium,
                color = Dim,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        Rule()

        MenuRow(
            label = "Scan setup code",
            sub = "Camera",
            onClick = {
                message = null
                if (granted) scanning = true else ask.launch(Manifest.permission.CAMERA)
            },
        )
        Rule()

        message?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                modifier = Modifier.padding(16.dp),
            )
            Rule()
        }

        SectionHeader("Now")
        MenuRow(
            label = "League",
            detail = if (hasLeague) leagueId else "not set",
            dim = !hasLeague,
        )
        MenuRow(
            label = "Login",
            detail = if (signedIn) "connected" else "public leagues only",
            dim = !signedIn,
        )
        Rule()

        Text(
            PUBLIC_NOTE,
            style = MaterialTheme.typography.bodyMedium,
            color = Faint,
            modifier = Modifier.padding(16.dp),
        )

        if (hasLeague) {
            Rule()
            MenuRow(label = "Done", onClick = onDone)
        }
        Column(Modifier.padding(bottom = 24.dp)) {}
    }
}

private const val SETUP_PAGE = "gi-os.github.io/BrightFantasy"

private const val PUBLIC_NOTE =
    "A public league needs only the league id — the cookies are what let ESPN show a " +
        "private one. They expire every few months; when the app says the login was " +
        "refused, scan a fresh code."
