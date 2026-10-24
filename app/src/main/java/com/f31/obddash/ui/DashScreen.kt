package com.f31.obddash.ui

import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.Balance
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Cached
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.DeviceThermostat
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material.icons.outlined.OilBarrel
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import com.f31.obddash.R
import com.f31.obddash.data.SENSOR_MAP
import com.f31.obddash.data.SensorId
import com.f31.obddash.data.formatValue
import java.util.Locale

private val TILE_ROW_1: List<Pair<SensorId, ImageVector>> = listOf(
    SensorId.WATER_TEMP to Icons.Outlined.DeviceThermostat,
    SensorId.OIL_TEMP to Icons.Outlined.OilBarrel,
    SensorId.INTAKE_TEMP to Icons.Outlined.Air,
    SensorId.MAP to Icons.Outlined.Cached,
    SensorId.THROTTLE to Icons.Outlined.Tune,
    SensorId.BATTERY to Icons.Outlined.BatteryChargingFull,
)

private val TILE_ROW_2: List<Pair<SensorId, ImageVector>> = listOf(
    SensorId.SPEED to Icons.Outlined.Speed,
    SensorId.FUEL_TRIM to Icons.Outlined.LocalGasStation,
    SensorId.AFR to Icons.Outlined.Balance,
    SensorId.OIL_PRESSURE to Icons.Outlined.Compress,
    SensorId.TRANS_TEMP to Icons.Outlined.Thermostat,
    SensorId.RUN_TIME to Icons.Outlined.Timer,
)

@Composable
fun DashScreen(values: Map<SensorId, Double>, unsupported: Set<SensorId>, configured: Set<SensorId>) {
    fun na(id: SensorId) = id in unsupported || (id !in configured && id != SensorId.BOOST)

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(u(6))) {
        // ---------------- hero: RPM · car · boost · gear
        Panel(Modifier.fillMaxWidth().weight(1.45f)) {
            Row(Modifier.fillMaxSize().padding(horizontal = u(8), vertical = u(4)), verticalAlignment = Alignment.CenterVertically) {
                val rpm = values[SensorId.RPM]
                ArcGauge(
                    Modifier.weight(1.25f).fillMaxHeight(),
                    value = rpm?.div(1000.0),
                    min = 0.0, max = 7.0, redFrom = 5.0,
                    ticks = (0..7).map { it.toDouble() },
                    tickLabel = { it.toInt().toString() },
                    title = "RPM x1000",
                    valueText = rpm?.let { String.format(Locale.US, "%.0f", it) } ?: "--",
                    unit = "",
                )
                Column(
                    Modifier.weight(1.05f).fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    MStripes(Modifier.width(u(90)).height(u(16)))
                    Image(
                        painterResource(R.drawable.car), contentDescription = "BMW 316i F31",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().weight(1f)
                    )
                }
                val boost = values[SensorId.BOOST]
                ArcGauge(
                    Modifier.weight(1f).fillMaxHeight(),
                    value = boost,
                    min = -1.0, max = 2.0, redFrom = 1.0,
                    ticks = listOf(-1.0, -0.5, 0.0, 0.5, 1.0, 1.5, 2.0),
                    tickLabel = { String.format(Locale.US, "%.1f", it) },
                    title = "BOOST",
                    valueText = boost?.let { String.format(Locale.US, "%.2f", it) } ?: "--",
                    unit = "bar",
                )
                Spacer(Modifier.width(u(6)))
                GearBox(Modifier.weight(0.32f).fillMaxHeight(0.65f), values[SensorId.GEAR], na(SensorId.GEAR))
            }
        }

        // ---------------- tiles
        Row(Modifier.fillMaxWidth().weight(0.78f), horizontalArrangement = Arrangement.spacedBy(u(6))) {
            TILE_ROW_1.forEach { (id, icon) ->
                SensorTile(Modifier.weight(1f).fillMaxHeight(), SENSOR_MAP.getValue(id), values[id], icon, na(id))
            }
        }
        Row(Modifier.fillMaxWidth().weight(0.66f), horizontalArrangement = Arrangement.spacedBy(u(6))) {
            TILE_ROW_2.forEach { (id, icon) ->
                SensorTile(
                    Modifier.weight(1f).fillMaxHeight(), SENSOR_MAP.getValue(id), values[id], icon, na(id),
                    showBar = id != SensorId.RUN_TIME
                )
            }
        }
    }
}

@Composable
private fun GearBox(modifier: Modifier, gear: Double?, unavailable: Boolean) {
    Panel(modifier) {
        Column(
            Modifier.fillMaxSize().padding(u(6)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            Text("GEAR", color = C.Dim, fontSize = t(10))
            Text(
                SENSOR_MAP.getValue(SensorId.GEAR).formatValue(gear),
                color = C.Text, fontSize = t(28), fontWeight = FontWeight.Bold
            )
            Box(Modifier.height(u(10))) {
                if (gear == null) Text(if (unavailable) "UNAVAILABLE" else "", color = C.Dim, fontSize = t(6))
            }
        }
    }
}
