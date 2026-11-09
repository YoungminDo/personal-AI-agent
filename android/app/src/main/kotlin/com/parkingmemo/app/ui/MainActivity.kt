package com.parkingmemo.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.parkingmemo.app.ParkingApp
import com.parkingmemo.app.TrackerService
import com.parkingmemo.app.TrackerState

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_ACTION = "tracker_action"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
        setContent { ParkingTheme { AppRoot() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** A notification asked for a tap because Android blocked a background start. */
    private fun handle(i: Intent?) {
        val action = i?.getStringExtra(EXTRA_ACTION) ?: return
        i.removeExtra(EXTRA_ACTION)
        TrackerService.send(this, action)
    }
}

private enum class Screen { SETUP, HOME, GUIDE, SENSORS }

@Composable
fun AppRoot() {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as ParkingApp
    var screen by remember { mutableStateOf(if (app.prefs.setupDone) Screen.HOME else Screen.SETUP) }
    val status by TrackerState.status.collectAsState()
    var wasGuiding by remember { mutableStateOf(false) }

    LaunchedEffect(status) {
        if (status == TrackerState.Status.GUIDING) {
            screen = Screen.GUIDE
            wasGuiding = true
        } else if (wasGuiding) {
            wasGuiding = false
            if (screen == Screen.GUIDE) screen = Screen.HOME
        }
    }

    when (screen) {
        Screen.SETUP -> SetupScreen(onDone = {
            app.prefs.setupDone = true
            screen = Screen.HOME
        })
        Screen.HOME -> HomeScreen(
            onStartGuide = {
                TrackerService.send(ctx, TrackerService.ACTION_START_GUIDE)
                screen = Screen.GUIDE
            },
            onOpenSetup = { screen = Screen.SETUP },
            onOpenSensors = { screen = Screen.SENSORS },
        )
        Screen.SENSORS -> SensorCheckScreen(onBack = { screen = Screen.HOME })
        Screen.GUIDE -> GuideScreen(
            onFound = { TrackerService.send(ctx, TrackerService.ACTION_FOUND) },
            onClose = {
                TrackerService.send(ctx, TrackerService.ACTION_STOP_GUIDE)
                screen = Screen.HOME
            },
        )
    }
}
