package com.f31.obddash

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import com.f31.obddash.data.SensorId
import com.f31.obddash.data.computeAlarms
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.f31.obddash.data.ConnState
import com.f31.obddash.data.ConnStatus
import com.f31.obddash.data.DashViewModel
import com.f31.obddash.ui.C
import com.f31.obddash.ui.CodesScreen
import com.f31.obddash.ui.DashHost
import com.f31.obddash.ui.Dot
import com.f31.obddash.ui.GraphsScreen
import com.f31.obddash.ui.LocalUiScale
import com.f31.obddash.ui.MStripes
import com.f31.obddash.ui.NavButton
import com.f31.obddash.ui.Panel
import com.f31.obddash.ui.SensorsScreen
import com.f31.obddash.ui.SettingsScreen
import com.f31.obddash.ui.TripsScreen
import com.f31.obddash.ui.ZeroHundredScreen
import com.f31.obddash.ui.t
import com.f31.obddash.ui.u
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Screen(val label: String, val icon: ImageVector) {
    DASH("DASH", Icons.Outlined.DirectionsCar),
    GRAPHS("GRAPHS", Icons.Outlined.Timeline),
    CODES("CODES", Icons.Outlined.ErrorOutline),
    ZERO100("0-100", Icons.Outlined.Timer),
    TRIPS("TRIPS", Icons.Outlined.Route),
    SETTINGS("SETTINGS", Icons.Outlined.Settings),
    SENSORS("SENSORS", Icons.Outlined.Sensors),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = C.Blue, background = C.Bg, surface = C.Panel)) {
                F31DashApp()
            }
        }
    }
}

@Composable
fun F31DashApp(vm: DashViewModel = viewModel()) {
    LaunchedEffect(Unit) { vm.start() }

    val values by vm.values.collectAsStateWithLifecycle()
    val unsupported by vm.unsupported.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val recording by vm.recording.collectAsStateWithLifecycle()
    val dashMode by vm.dashMode.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf(Screen.DASH) }

    val alarms = remember(values) { computeAlarms(values) }
    val alarmKey = alarms.map { it.id }.toSet()
    var silenced by remember { mutableStateOf(emptySet<SensorId>()) }
    // a new alarm (not silenced) beeps every 2 s until it clears or is tapped
    val beep = alarmKey.isNotEmpty() && !silenced.containsAll(alarmKey)
    LaunchedEffect(beep) {
        if (!beep) return@LaunchedEffect
        val tg = try { ToneGenerator(AudioManager.STREAM_ALARM, 90) } catch (e: Exception) { null }
        try {
            while (true) {
                tg?.startTone(ToneGenerator.TONE_PROP_BEEP2, 500)
                delay(2000)
            }
        } finally {
            tg?.release()
        }
    }
    LaunchedEffect(alarmKey) { silenced = silenced intersect alarmKey }

    BoxWithConstraints(Modifier.fillMaxSize().background(C.Bg)) {
        val scale = minOf(maxWidth.value / 900f, maxHeight.value / 410f).coerceIn(0.55f, 2.2f)
        CompositionLocalProvider(LocalUiScale provides scale) {
            Column(
                Modifier.fillMaxSize().padding(u(8)),
                verticalArrangement = Arrangement.spacedBy(u(6))
            ) {
                TopBar(Modifier.fillMaxWidth().height(u(40)), screen, status, recording, dashMode, { vm.setDashMode(it) }) { screen = it }

                Column(Modifier.fillMaxWidth().weight(1f)) {
                    when (screen) {
                        Screen.DASH -> DashHost(vm, alarms) { silenced = alarmKey }
                        Screen.GRAPHS -> GraphsScreen(history)
                        Screen.CODES -> CodesScreen(vm)
                        Screen.ZERO100 -> ZeroHundredScreen(vm)
                        Screen.TRIPS -> TripsScreen(vm)
                        Screen.SETTINGS -> SettingsScreen(vm)
                        Screen.SENSORS -> SensorsScreen(vm)
                    }
                }

                Row(Modifier.fillMaxWidth().height(u(40)), horizontalArrangement = Arrangement.spacedBy(u(6))) {
                    Screen.entries.forEach { s ->
                        NavButton(Modifier.weight(1f).fillMaxHeight(), s.label, s.icon, s == screen) { screen = s }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar(
    modifier: Modifier, screen: Screen, status: ConnStatus, recording: Boolean,
    dashMode: Int, onDashMode: (Int) -> Unit, onSelect: (Screen) -> Unit,
) {
    val now by produceState(Date()) {
        while (true) { value = Date(); delay(1000) }
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        MStripes(Modifier.width(u(40)).height(u(14)))
        Spacer(Modifier.width(u(8)))
        Column {
            Text("BMW 316i F31", color = C.Text, fontSize = t(13), fontWeight = FontWeight.Bold)
            Text("N13 1.6 TURBO", color = C.Dim, fontSize = t(9))
        }
        Spacer(Modifier.weight(1f))
        if (screen == Screen.DASH) {
            listOf("DAILY", "SPORT").forEachIndexed { i, label ->
                NavButton(Modifier.width(u(78)).fillMaxHeight(0.85f), label, null, dashMode == i, 10f) { onDashMode(i) }
                Spacer(Modifier.width(u(6)))
            }
            Spacer(Modifier.weight(1f))
        }

        val dotColor = when (status.state) {
            ConnState.CONNECTED -> C.Green
            ConnState.DEMO -> C.Amber
            ConnState.SEARCHING, ConnState.CONNECTING -> C.Blue
            ConnState.ERROR -> C.Red
            ConnState.DISCONNECTED -> C.Dim
        }
        val stateText = when (status.state) {
            ConnState.CONNECTED -> "ENET CONNECTED"
            ConnState.DEMO -> "DEMO MODE"
            ConnState.SEARCHING -> "SEARCHING…"
            ConnState.CONNECTING -> "CONNECTING…"
            ConnState.ERROR -> "ENET ERROR"
            ConnState.DISCONNECTED -> "ENET DISCONNECTED"
        }
        Dot(dotColor, u(9))
        Spacer(Modifier.width(u(8)))
        Column(Modifier.width(u(150))) {
            Row {
                Text(stateText, color = C.Text, fontSize = t(9), maxLines = 1)
                if (recording) Text("  ● REC", color = C.Red, fontSize = t(9), fontWeight = FontWeight.Bold, maxLines = 1)
            }
            Text(status.detail, color = C.Dim, fontSize = t(7), maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(now), color = C.Text, fontSize = t(13))
            Text(SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(now), color = C.Dim, fontSize = t(7))
        }
    }
}
