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
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.foundation.Canvas
import com.f31.obddash.data.Alarm
import com.f31.obddash.data.REDLINE_HOT_RPM
import com.f31.obddash.data.redlineRpm
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
fun SportDash(
    values: Map<SensorId, Double>,
    unsupported: Set<SensorId>,
    configured: Set<SensorId>,
    alarms: List<Alarm>,
    onAlarmTap: () -> Unit,
    peaks: Map<SensorId, Double>,
    onResetPeaks: () -> Unit,
) {
    fun na(id: SensorId) = id in unsupported || (id !in configured && id != SensorId.BOOST)
    val alarmIds = alarms.map { it.id }.toSet()

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(u(6))) {
        // ---------------- shift light
        ShiftLight(
            Modifier.fillMaxWidth().height(u(7)),
            values[SensorId.RPM], redlineRpm(values[SensorId.OIL_TEMP])
        )
        // ---------------- hero: RPM · car · boost · gear
        Panel(Modifier.fillMaxWidth().weight(1.2f)) {
            Row(Modifier.fillMaxSize().padding(horizontal = u(8), vertical = u(4)), verticalAlignment = Alignment.CenterVertically) {
                val rpm = values[SensorId.RPM]
                val rpmAnim by animateFloatAsState((rpm ?: 0.0).toFloat(), tween(120, easing = LinearEasing), label = "rpm")
                // 1. variable redline from oil temperature
                val oilT = values[SensorId.OIL_TEMP]
                val redline = redlineRpm(oilT)
                val redlineAnim by animateFloatAsState(redline.toFloat(), tween(800), label = "redline")
                val cold = redline < REDLINE_HOT_RPM - 1
                val overLimit = rpm != null && rpm >= redline
                ArcGauge(
                    Modifier.weight(1.25f).fillMaxHeight().clickable(onClick = onResetPeaks),
                    value = if (rpm == null) null else rpmAnim / 1000.0,
                    min = 0.0, max = 7.0, redFrom = redlineAnim / 1000.0,
                    ticks = (0..7).map { it.toDouble() },
                    tickLabel = { it.toInt().toString() },
                    title = "RPM x1000",
                    valueText = rpm?.let { String.format(Locale.US, "%.0f", it) } ?: "--",
                    unit = if (cold) String.format(Locale.US, "OIL COLD · LIMIT %.0f", redline) else "",
                    valueColor = if (overLimit) C.Red else C.Text,
                    unitColor = C.Amber,
                    peak = peaks[SensorId.RPM]?.div(1000.0),
                )
                Column(
                    Modifier.weight(1.05f).fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 2. alarm banner (tap = silence)
                    if (alarms.isNotEmpty()) {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(u(6)))
                                .background(C.Red.copy(alpha = 0.35f + blinkAlpha()))
                                .clickable(onClick = onAlarmTap)
                                .padding(horizontal = u(10), vertical = u(2))
                        ) {
                            Text(
                                "⚠ " + alarms.joinToString("  ·  ") { it.text },
                                color = Color.White, fontSize = t(10), fontWeight = FontWeight.Bold, maxLines = 1
                            )
                        }
                    } else {
                        MStripes(Modifier.width(u(90)).height(u(16)))
                    }
                    // 3. smaller car, big speed
                    Image(
                        painterResource(R.drawable.car), contentDescription = "BMW 316i F31",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(0.58f)
                            .drawBehind {
                                // soft round glow behind the car — fades fully to transparent, no edge
                                val r = minOf(size.width * 0.42f, size.height * 0.75f)
                                drawRect(
                                    Brush.radialGradient(
                                        listOf(C.Blue.copy(alpha = 0.16f), C.Blue.copy(alpha = 0.05f), Color.Transparent),
                                        center = Offset(size.width / 2f, size.height * 0.6f),
                                        radius = r
                                    )
                                )
                            }
                    )
                    val speed = values[SensorId.SPEED]
                    Column(Modifier.weight(0.42f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                speed?.let { String.format(Locale.US, "%.0f", it) } ?: "--",
                                color = C.Text, fontSize = t(36), fontWeight = FontWeight.Bold, maxLines = 1
                            )
                            Spacer(Modifier.width(u(6)))
                            Text("km/h", color = C.Dim, fontSize = t(11), modifier = Modifier.padding(bottom = u(7)))
                        }
                        // 5. fuel consumption
                        val l100 = values[SensorId.CONSUMPTION]
                        val lh = values[SensorId.FUEL_RATE]
                        Text(
                            when {
                                l100 != null -> String.format(Locale.US, "%.1f L/100 km", l100)
                                lh != null -> String.format(Locale.US, "%.1f L/h", lh)
                                else -> ""
                            },
                            color = C.BlueLight, fontSize = t(11), maxLines = 1
                        )
                    }
                }
                val boost = values[SensorId.BOOST]
                val boostAnim by animateFloatAsState((boost ?: 0.0).toFloat(), tween(120, easing = LinearEasing), label = "boost")
                ArcGauge(
                    Modifier.weight(1f).fillMaxHeight().clickable(onClick = onResetPeaks),
                    value = if (boost == null) null else boostAnim.toDouble(),
                    min = -1.0, max = 2.0, redFrom = 1.0,
                    ticks = listOf(-1.0, -0.5, 0.0, 0.5, 1.0, 1.5, 2.0),
                    tickLabel = { String.format(Locale.US, "%.1f", it) },
                    title = "BOOST",
                    valueText = boost?.let { String.format(Locale.US, "%.2f", it) } ?: "--",
                    unit = peaks[SensorId.BOOST]?.let { String.format(Locale.US, "bar · max %.2f", it) } ?: "bar",
                    peak = peaks[SensorId.BOOST],
                )
                Spacer(Modifier.width(u(6)))
                GearBox(Modifier.weight(0.42f).fillMaxHeight(0.8f), values[SensorId.GEAR], na(SensorId.GEAR))
            }
        }

        // ---------------- tiles
        Row(Modifier.fillMaxWidth().weight(0.8f), horizontalArrangement = Arrangement.spacedBy(u(6))) {
            TILE_ROW_1.forEach { (id, icon) ->
                SensorTile(Modifier.weight(1f).fillMaxHeight(), SENSOR_MAP.getValue(id), values[id], icon, na(id), alarm = id in alarmIds)
            }
        }
        Row(Modifier.fillMaxWidth().weight(0.8f), horizontalArrangement = Arrangement.spacedBy(u(6))) {
            TILE_ROW_2.forEach { (id, icon) ->
                SensorTile(
                    Modifier.weight(1f).fillMaxHeight(), SENSOR_MAP.getValue(id), values[id], icon, na(id),
                    showBar = id != SensorId.RUN_TIME, alarm = id in alarmIds
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
                color = C.Text, fontSize = t(44), fontWeight = FontWeight.Bold, maxLines = 1
            )
            Box(Modifier.height(u(14))) {
                if (gear == null) Text(if (unavailable) "UNAVAILABLE" else "", color = C.Dim, fontSize = t(7), maxLines = 1)
            }
        }
    }
}

/**
 * RPM bar: fills from idle (1000 rpm) up to the oil-temp dependent limit.
 * Box colour depends on its rpm: green up to 70% of the limit, amber up to 90%, red above.
 * Uses the smoothed rpm so it moves fluidly between OBD updates; the leading box fills partially.
 * At/over the limit the whole bar flashes red.
 */
@Composable
fun ShiftLight(modifier: Modifier, rpm: Double?, redline: Double) {
    val blink = blinkAlpha()
    val rpmAnim by animateFloatAsState((rpm ?: 0.0).toFloat(), tween(150, easing = LinearEasing), label = "shiftRpm")
    val redAnim by animateFloatAsState(redline.toFloat(), tween(800), label = "shiftRedline")
    Canvas(modifier) {
        val n = 15
        val gap = size.height * 0.6f
        val w = (size.width - gap * (n - 1)) / n
        val start = 1000f
        val span = (redAnim - start).coerceAtLeast(500f)
        val pos = if (rpm == null) 0f else ((rpmAnim - start) / span * n).coerceIn(0f, n.toFloat())
        val over = rpm != null && rpmAnim >= redAnim
        val radius = CornerRadius(size.height / 2)
        for (i in 0 until n) {
            val x = i * (w + gap)
            val boxRpmFrac = (i + 1f) / n
            val col = when {
                boxRpmFrac <= 0.70f -> C.Green
                boxRpmFrac <= 0.90f -> C.Amber
                else -> C.Red
            }
            drawRoundRect(C.Faint, Offset(x, 0f), Size(w, size.height), radius)
            if (over) {
                drawRoundRect(C.Red.copy(alpha = (0.45f + blink).coerceIn(0f, 1f)), Offset(x, 0f), Size(w, size.height), radius)
            } else {
                val fill = (pos - i).coerceIn(0f, 1f)
                if (fill > 0f) drawRoundRect(col, Offset(x, 0f), Size(w * fill, size.height), radius)
            }
        }
    }
}
