package com.f31.obddash.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import com.f31.obddash.data.SensorDef
import com.f31.obddash.data.formatValue
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun Panel(
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(u(10))
    Box(
        modifier
            .clip(shape)
            .background(if (highlight) C.BlueDeep.copy(alpha = 0.35f) else C.Panel)
            .border(if (highlight) u(1.5f) else u(1), if (highlight) C.Blue else C.Border, shape)
    ) { content() }
}

/** The three M-style stripes. */
@Composable
fun MStripes(modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width / 3.3f
        val slant = size.height * 0.45f
        listOf(C.MLight, C.MDark, C.MRed).forEachIndexed { i, col ->
            val x = i * w * 1.1f
            val p = Path().apply {
                moveTo(x + slant, 0f); lineTo(x + w + slant * 0.0f + slant, 0f)
                lineTo(x + w, size.height); lineTo(x, size.height); close()
            }
            drawPath(p, col)
        }
    }
}

/**
 * Segmented arc gauge like the reference design (RPM, boost).
 * [ticks] are the major tick values shown as numbers.
 */
@Composable
fun ArcGauge(
    modifier: Modifier,
    value: Double?,
    min: Double,
    max: Double,
    redFrom: Double,
    ticks: List<Double>,
    tickLabel: (Double) -> String,
    title: String,
    valueText: String,
    unit: String,
) {
    val tm = rememberTextMeasurer()
    Canvas(modifier) {
        val startDeg = 175f
        val sweepDeg = 190f
        val r = kotlin.math.min(size.width / 2f * 0.93f, size.height / 1.18f)
        val cx = size.width / 2f
        val cy = size.height / 2f + r * 0.46f
        val thick = r * 0.12f
        val n = 64
        val segW = (2 * Math.PI * r * sweepDeg / 360f / n).toFloat() * 0.55f

        fun pt(deg: Float, rad: Float): Offset {
            val a = Math.toRadians(deg.toDouble())
            return Offset(cx + rad * cos(a).toFloat(), cy + rad * sin(a).toFloat())
        }

        for (i in 0 until n) {
            val f = (i + 0.5f) / n
            val segVal = min + f * (max - min)
            val deg = startDeg + sweepDeg * f
            val red = segVal >= redFrom
            val base = if (red) C.Red else lerpColor(C.Blue, C.BlueLight, (f / ((redFrom - min) / (max - min)).toFloat()).coerceIn(0f, 1f))
            val active = value != null && segVal <= value
            val col = when {
                value == null -> base.copy(alpha = 0.85f)
                active -> base
                else -> base.copy(alpha = 0.18f)
            }
            if (active) {
                drawLine(base.copy(alpha = 0.25f), pt(deg, r + thick * 0.15f), pt(deg, r - thick * 1.15f), segW * 2.2f, StrokeCap.Round)
            }
            drawLine(col, pt(deg, r), pt(deg, r - thick), segW)
        }

        val labelStyle = TextStyle(color = C.Text, fontSize = (r * 0.11f).toSp(), fontWeight = FontWeight.SemiBold)
        ticks.forEach { tv ->
            val f = ((tv - min) / (max - min)).toFloat()
            val deg = startDeg + sweepDeg * f
            drawLine(Color.White, pt(deg, r + thick * 0.15f), pt(deg, r - thick * 1.35f), r * 0.012f + 1f)
            val lbl = tm.measure(tickLabel(tv), labelStyle)
            val c = pt(deg, r - thick * 1.35f - r * 0.12f)
            drawText(lbl, topLeft = Offset(c.x - lbl.size.width / 2f, c.y - lbl.size.height / 2f))
        }

        val titleL = tm.measure(title, TextStyle(color = C.Dim, fontSize = (r * 0.10f).toSp()))
        drawText(titleL, topLeft = Offset(cx - titleL.size.width / 2f, cy - r * 0.48f))
        val valL = tm.measure(
            valueText,
            TextStyle(color = C.Text, fontSize = (r * 0.24f).toSp(), fontWeight = FontWeight.Bold)
        )
        drawText(valL, topLeft = Offset(cx - valL.size.width / 2f, cy - r * 0.33f))
        if (unit.isNotEmpty()) {
            val unitL = tm.measure(unit, TextStyle(color = C.Dim, fontSize = (r * 0.09f).toSp()))
            drawText(unitL, topLeft = Offset(cx - unitL.size.width / 2f, cy - r * 0.02f))
        }
    }
}

private fun lerpColor(a: Color, b: Color, f: Float) = Color(
    red = a.red + (b.red - a.red) * f,
    green = a.green + (b.green - a.green) * f,
    blue = a.blue + (b.blue - a.blue) * f,
    alpha = 1f
)

@Composable
fun SegmentBar(modifier: Modifier, fraction: Float?, color: Color, segments: Int = 5) {
    Canvas(modifier) {
        val gap = size.height * 0.6f
        val w = (size.width - gap * (segments - 1)) / segments
        for (i in 0 until segments) {
            val x = i * (w + gap)
            drawRoundRect(C.Faint, Offset(x, 0f), Size(w, size.height), CornerRadius(1f))
            if (fraction != null) {
                val fill = ((fraction * segments) - i).coerceIn(0f, 1f)
                if (fill > 0f) drawRoundRect(color, Offset(x, 0f), Size(w * fill, size.height), CornerRadius(1f))
            }
        }
    }
}

@Composable
fun SensorTile(
    modifier: Modifier,
    def: SensorDef,
    value: Double?,
    icon: ImageVector,
    unavailable: Boolean,
    showBar: Boolean = true,
) {
    val warn = value != null &&
        ((def.warnAbove != null && value > def.warnAbove) || (def.warnBelow != null && value < def.warnBelow))
    Panel(modifier) {
        Column(Modifier.fillMaxSize().padding(horizontal = u(10), vertical = u(6))) {
            Row(Modifier.fillMaxWidth().weight(1f)) {
                Icon(icon, null, tint = if (warn) C.Red else C.Text, modifier = Modifier.size(u(26)))
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Text(def.label, color = C.Text, fontSize = t(9), maxLines = 1)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            if (unavailable) "N/A" else def.formatValue(value),
                            color = if (warn) C.Red else C.Text,
                            fontSize = t(18), fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false
                        )
                        Spacer(Modifier.width(u(4)))
                        Text(def.unit, color = C.Dim, fontSize = t(8), modifier = Modifier.padding(bottom = u(3)))
                    }
                }
            }
            if (showBar) {
                val frac = value?.let { ((it - def.min) / (def.max - def.min)).toFloat().coerceIn(0f, 1f) }
                SegmentBar(Modifier.fillMaxWidth().height(u(4)), frac, if (warn) C.Red else C.Blue)
                Spacer(Modifier.height(u(3)))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(def.min.fmtScale(), color = C.Dim, fontSize = t(7))
                    Text(def.midLabel, color = C.Dim, fontSize = t(7))
                    Text(def.max.fmtScale(), color = C.Dim, fontSize = t(7))
                }
            } else {
                SegmentBar(Modifier.fillMaxWidth().height(u(4)), null, C.Blue)
                Spacer(Modifier.height(u(3)))
                Text(" ", fontSize = t(7))
            }
        }
    }
}

private fun Double.fmtScale(): String = if (this == Math.floor(this)) toInt().toString() else toString()

@Composable
fun NavButton(
    modifier: Modifier,
    label: String,
    icon: ImageVector?,
    selected: Boolean,
    textSize: Float = 11f,
    onClick: () -> Unit,
) {
    Panel(modifier.clickable(onClick = onClick), highlight = selected) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = u(6)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                Icon(icon, null, tint = C.Text, modifier = Modifier.size(u(textSize * 1.7f)))
                Spacer(Modifier.width(u(8)))
            }
            Text(
                label, color = C.Text, fontSize = t(textSize), fontWeight = FontWeight.Medium,
                maxLines = 1, textAlign = TextAlign.Center, letterSpacing = t(0.8f)
            )
        }
    }
}

@Composable
fun Dot(color: Color, size: Dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(50)).background(color))
}
