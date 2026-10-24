package com.f31.obddash.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import com.f31.obddash.R
import com.f31.obddash.data.Alarm
import com.f31.obddash.data.DashViewModel
import com.f31.obddash.data.SensorId
import com.f31.obddash.data.TripLive
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
/** DASH screen: DAILY (page 0) and SPORT (page 1), swipe or use the switch in the top bar. */
@Composable
fun DashHost(vm: DashViewModel, alarms: List<Alarm>, onAlarmTap: () -> Unit) {
    val values by vm.values.collectAsState()
    val unsupported by vm.unsupported.collectAsState()
    val sources by vm.sources.collectAsState()
    val peaks by vm.peaks.collectAsState()
    val trip by vm.tripLive.collectAsState()
    val mode by vm.dashMode.collectAsState()

    val pager = rememberPagerState(initialPage = mode) { 2 }
    // switch in the top bar → page
    LaunchedEffect(mode) { if (pager.currentPage != mode) pager.animateScrollToPage(mode) }
    // swipe → remember the mode
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { if (it != vm.dashMode.value) vm.setDashMode(it) }
    }

    HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), pageSpacing = u(8)) { page ->
        if (page == 0) DailyDash(values, alarms, onAlarmTap, trip)
        else SportDash(values, unsupported, sources.keys, alarms, onAlarmTap, peaks) { vm.resetPeaks() }
    }
}

@Composable
fun DailyDash(values: Map<SensorId, Double>, alarms: List<Alarm>, onAlarmTap: () -> Unit, trip: TripLive) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(u(6))) {

        // ---------------- engine status
        EngineStatus(Modifier.weight(0.95f).fillMaxHeight(), values, alarms, onAlarmTap)

        // ---------------- speed · gear · car
        Panel(Modifier.weight(1.5f).fillMaxHeight()) {
            Column(
                Modifier.fillMaxSize().padding(u(8)),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val speed = values[SensorId.SPEED]
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        speed?.let { String.format(Locale.US, "%.0f", it) } ?: "--",
                        color = C.Text, fontSize = t(96), fontWeight = FontWeight.Bold, maxLines = 1
                    )
                    Spacer(Modifier.width(u(8)))
                    Text("km/h", color = C.Dim, fontSize = t(16), modifier = Modifier.padding(bottom = u(18)))
                }
                val g = values[SensorId.GEAR]?.toInt()
                Text(
                    if (g != null && g in 1..8) "GEAR  $g" else "GEAR  –",
                    color = C.BlueLight, fontSize = t(18), fontWeight = FontWeight.SemiBold, letterSpacing = t(2)
                )
                Image(
                    painterResource(R.drawable.car), contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(top = u(4))
                        .drawBehind {
                            drawOval(
                                Brush.radialGradient(
                                    listOf(C.Blue.copy(alpha = 0.18f), Color.Transparent),
                                    center = Offset(size.width / 2f, size.height * 0.62f),
                                    radius = size.width * 0.45f
                                ),
                                topLeft = Offset(size.width * 0.05f, size.height * 0.15f),
                                size = Size(size.width * 0.9f, size.height * 0.95f)
                            )
                        }
                )
            }
        }

        // ---------------- fuel · this drive
        Panel(Modifier.weight(0.95f).fillMaxHeight()) {
            Column(Modifier.fillMaxSize().padding(u(12)), verticalArrangement = Arrangement.spacedBy(u(4))) {
                Text("CONSUMPTION", color = C.Dim, fontSize = t(10))
                val l100 = values[SensorId.CONSUMPTION]
                val lh = values[SensorId.FUEL_RATE]
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        when {
                            l100 != null -> String.format(Locale.US, "%.1f", l100)
                            lh != null -> String.format(Locale.US, "%.1f", lh)
                            else -> "--"
                        },
                        color = C.Text, fontSize = t(38), fontWeight = FontWeight.Bold, maxLines = 1
                    )
                    Spacer(Modifier.width(u(6)))
                    Text(if (l100 != null) "L/100 km" else "L/h", color = C.Dim, fontSize = t(11),
                        modifier = Modifier.padding(bottom = u(8)))
                }
                Spacer(Modifier.height(u(8)))
                Box(Modifier.fillMaxWidth().height(u(1)).background(C.Border))
                Spacer(Modifier.height(u(8)))
                Text("THIS DRIVE", color = C.Dim, fontSize = t(10))
                MiniRow("Distance", if (trip.active) String.format(Locale.US, "%.1f km", trip.km) else "--")
                MiniRow("Average", trip.avgL100?.let { String.format(Locale.US, "%.1f L/100", it) } ?: "--")
                MiniRow("Time", if (trip.active) String.format(Locale.US, "%d:%02d", trip.durationS / 60, trip.durationS % 60) else "--")
                MiniRow("Fuel used", if (trip.active) String.format(Locale.US, "%.2f L", trip.fuelL) else "--")
            }
        }
    }
}

@Composable
private fun EngineStatus(modifier: Modifier, values: Map<SensorId, Double>, alarms: List<Alarm>, onAlarmTap: () -> Unit) {
    val oil = values[SensorId.OIL_TEMP]
    val water = values[SensorId.WATER_TEMP]
    val (state, col) = when {
        alarms.isNotEmpty() -> alarms.joinToString("\n") { it.text } to C.Red
        oil == null && water == null -> "NO DATA" to C.Dim
        (oil ?: water ?: 0.0) < 70.0 -> "WARMING UP" to C.Blue
        else -> "READY" to C.Green
    }
    // warm-up progress from oil temperature (20 → 85 °C)
    val warm = ((((oil ?: water) ?: 20.0) - 20.0) / 65.0).toFloat().coerceIn(0f, 1f)

    Panel(modifier.clickable(enabled = alarms.isNotEmpty(), onClick = onAlarmTap)) {
        if (alarms.isNotEmpty()) Box(Modifier.fillMaxSize().background(C.Red.copy(alpha = blinkAlpha() * 0.6f)))
        Column(Modifier.fillMaxSize().padding(u(12)), verticalArrangement = Arrangement.spacedBy(u(4))) {
            Text("ENGINE", color = C.Dim, fontSize = t(10))
            Text(state, color = if (alarms.isNotEmpty()) Color.White else col,
                fontSize = t(if (alarms.isNotEmpty()) 16 else 24), fontWeight = FontWeight.Bold)
            SegmentBar(Modifier.fillMaxWidth().height(u(6)), warm, if (warm >= 1f) C.Green else C.Blue, segments = 10)
            if (alarms.isNotEmpty()) Text("tap to silence", color = Color.White, fontSize = t(8))
            Spacer(Modifier.height(u(8)))
            Box(Modifier.fillMaxWidth().height(u(1)).background(C.Border))
            Spacer(Modifier.height(u(8)))
            MiniRow("Coolant", water?.let { String.format(Locale.US, "%.0f °C", it) } ?: "--")
            MiniRow("Oil", oil?.let { String.format(Locale.US, "%.0f °C", it) } ?: "--")
            MiniRow("Oil pressure", values[SensorId.OIL_PRESSURE]?.let { String.format(Locale.US, "%.1f bar", it) } ?: "--")
            MiniRow("Battery", values[SensorId.BATTERY]?.let { String.format(Locale.US, "%.1f V", it) } ?: "--")
            MiniRow("Gearbox", values[SensorId.TRANS_TEMP]?.let { String.format(Locale.US, "%.0f °C", it) } ?: "--")
        }
    }
}

@Composable
private fun MiniRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = C.Dim, fontSize = t(11))
        Spacer(Modifier.weight(1f))
        Text(value, color = C.Text, fontSize = t(14), fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}
