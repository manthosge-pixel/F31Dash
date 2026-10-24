package com.f31.obddash.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import com.f31.obddash.data.ConnState
import com.f31.obddash.data.DashViewModel
import com.f31.obddash.data.Dtc
import com.f31.obddash.data.HISTORY_IDS
import com.f31.obddash.data.PerfPhase
import com.f31.obddash.data.SENSORS
import com.f31.obddash.data.SENSOR_MAP
import com.f31.obddash.data.SensorId
import com.f31.obddash.data.formatValue
import com.f31.obddash.enet.HsfzClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

// ======================================================================= GRAPHS

@Composable
fun GraphsScreen(history: Map<SensorId, List<Float>>) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(u(6))) {
        HISTORY_IDS.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(u(6))) {
                row.forEach { id ->
                    val def = SENSOR_MAP.getValue(id)
                    val data = history[id].orEmpty()
                    Panel(Modifier.weight(1f).fillMaxHeight()) {
                        Column(Modifier.fillMaxSize().padding(u(8))) {
                            Row(Modifier.fillMaxWidth()) {
                                Text(def.label, color = C.Dim, fontSize = t(10))
                                Spacer(Modifier.weight(1f))
                                val last = data.lastOrNull()?.takeIf { !it.isNaN() }?.toDouble()
                                Text("${def.formatValue(last)} ${def.unit}", color = C.Text, fontSize = t(12), fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.height(u(4)))
                            LineChart(Modifier.fillMaxWidth().weight(1f), data, def.min.toFloat(), def.max.toFloat())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LineChart(modifier: Modifier, data: List<Float>, min: Float, max: Float) {
    Canvas(modifier) {
        for (i in 1..3) {
            val y = size.height * i / 4f
            drawLine(C.Faint, Offset(0f, y), Offset(size.width, y), 1f)
        }
        if (data.size < 2) return@Canvas
        val path = Path()
        var started = false
        val step = size.width / (300 - 1)
        val offset = 300 - data.size
        data.forEachIndexed { i, v ->
            if (v.isNaN()) { started = false; return@forEachIndexed }
            val x = (offset + i) * step
            val y = size.height * (1f - ((v - min) / (max - min)).coerceIn(0f, 1f))
            if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
        }
        drawPath(path, C.Blue, style = Stroke(width = 2.5f))
    }
}

// ======================================================================= CODES

@Composable
fun CodesScreen(vm: DashViewModel) {
    val scope = rememberCoroutineScope()
    var ecu by remember { mutableStateOf(HsfzClient.ECU_DME) }
    val codes = remember { mutableStateListOf<Dtc>() }
    var msg by remember { mutableStateOf("Press READ to get the fault codes.") }
    var busy by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(u(6))) {
        Panel(Modifier.weight(0.32f).fillMaxHeight()) {
            Column(Modifier.fillMaxSize().padding(u(10)), verticalArrangement = Arrangement.spacedBy(u(8))) {
                Text("CONTROL UNIT", color = C.Dim, fontSize = t(10))
                listOf(HsfzClient.ECU_DME to "DME · engine (12)", HsfzClient.ECU_EGS to "EGS · gearbox (18)").forEach { (addr, name) ->
                    NavButton(Modifier.fillMaxWidth().height(u(34)), name, null, ecu == addr, 10f) { ecu = addr }
                }
                Spacer(Modifier.weight(1f))
                Button(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            vm.readDtcs(ecu).onSuccess {
                                codes.clear(); codes.addAll(it)
                                msg = if (it.isEmpty()) "No fault codes stored." else "${it.size} code(s) found."
                            }.onFailure { msg = "Error: ${it.message}" }
                            busy = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = C.Blue),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("READ") }
                OutlinedButton(
                    enabled = !busy, onClick = { confirmClear = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("CLEAR CODES", color = C.Red) }
            }
        }
        Panel(Modifier.weight(0.68f).fillMaxHeight()) {
            Column(Modifier.fillMaxSize().padding(u(10))) {
                Text(msg, color = C.Dim, fontSize = t(11))
                Spacer(Modifier.height(u(6)))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(u(4))) {
                    items(codes) { d ->
                        Row(Modifier.fillMaxWidth().padding(vertical = u(4)), verticalAlignment = Alignment.CenterVertically) {
                            Dot(if (d.active) C.Red else C.Amber, u(8))
                            Spacer(Modifier.width(u(8)))
                            Text(d.codeHex, color = C.Text, fontSize = t(15), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(u(12)))
                            Text(if (d.active) "ACTIVE" else "STORED", color = C.Dim, fontSize = t(10))
                            Spacer(Modifier.weight(1f))
                            Text("status ${d.statusHex}", color = C.Dim, fontSize = t(9), fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear fault codes?") },
            text = { Text("This erases the stored codes in the selected control unit. Engine off, ignition on.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false; busy = true
                    scope.launch {
                        vm.clearDtcs(ecu).onSuccess { codes.clear(); msg = "Codes cleared." }
                            .onFailure { msg = "Error: ${it.message}" }
                        busy = false
                    }
                }) { Text("CLEAR", color = C.Red) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("CANCEL") } }
        )
    }
}

// ======================================================================= 0-100

@Composable
fun ZeroHundredScreen(vm: DashViewModel) {
    val perf by vm.perf.collectAsState()
    val values by vm.values.collectAsState()
    val elapsed by produceState(0.0, perf.phase, perf.startMs) {
        while (perf.phase == PerfPhase.RUNNING) {
            value = (System.currentTimeMillis() - perf.startMs) / 1000.0
            delay(30)
        }
    }
    val speed = values[SensorId.SPEED]

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(u(6))) {
        Panel(Modifier.weight(1.4f).fillMaxHeight()) {
            Column(
                Modifier.fillMaxSize().padding(u(12)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                val (state, col) = when (perf.phase) {
                    PerfPhase.WAIT_STOP -> "STOP THE CAR TO ARM" to C.Amber
                    PerfPhase.READY -> "READY · GO!" to C.Green
                    PerfPhase.RUNNING -> "RUNNING" to C.Blue
                }
                Text(state, color = col, fontSize = t(14), fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(u(8)))
                val shown = if (perf.phase == PerfPhase.RUNNING) elapsed else perf.lastTime
                Text(
                    shown?.let { String.format(Locale.US, "%.2f s", it) } ?: "-.-- s",
                    color = C.Text, fontSize = t(64), fontWeight = FontWeight.Bold
                )
                Text("0 – 100 km/h", color = C.Dim, fontSize = t(12))
            }
        }
        Column(Modifier.weight(0.6f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(u(6))) {
            StatBox(Modifier.weight(1f), "SPEED", speed?.let { String.format(Locale.US, "%.0f km/h", it) } ?: "--")
            StatBox(Modifier.weight(1f), "BEST", perf.best?.let { String.format(Locale.US, "%.2f s", it) } ?: "--")
            OutlinedButton(onClick = { vm.resetPerfBest() }, modifier = Modifier.fillMaxWidth()) {
                Text("RESET", color = C.Text)
            }
        }
    }
}

@Composable
private fun StatBox(modifier: Modifier, label: String, value: String) {
    Panel(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxSize().padding(u(10)), verticalArrangement = Arrangement.Center) {
            Text(label, color = C.Dim, fontSize = t(10))
            Text(value, color = C.Text, fontSize = t(26), fontWeight = FontWeight.Bold)
        }
    }
}

// ======================================================================= TRACK

@Composable
fun TrackScreen() {
    Panel(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("TRACK · lap timer with GPS – next version", color = C.Dim, fontSize = t(14))
        }
    }
}

// ======================================================================= SETTINGS

@Composable
fun SettingsScreen(vm: DashViewModel) {
    val status by vm.status.collectAsState()
    val demo by vm.demo.collectAsState()
    val savedIp by vm.manualIp.collectAsState()
    val rate by vm.pollRate.collectAsState()
    var ip by remember(savedIp) { mutableStateOf(savedIp) }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(u(6))) {
        Panel(Modifier.weight(1f).fillMaxHeight()) {
            Column(Modifier.fillMaxSize().padding(u(12)), verticalArrangement = Arrangement.spacedBy(u(10))) {
                Text("CONNECTION", color = C.Dim, fontSize = t(10))
                Text("${status.state}  ·  ${status.detail}", color = C.Text, fontSize = t(12))
                if (status.state == ConnState.CONNECTED) {
                    Text(String.format(Locale.US, "%.1f requests/s", rate), color = C.Dim, fontSize = t(10))
                }
                OutlinedTextField(
                    value = ip, onValueChange = { ip = it },
                    label = { Text("Car IP (empty = auto-detect)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(u(8))) {
                    Button(
                        onClick = { vm.setManualIp(ip); vm.restart() },
                        colors = ButtonDefaults.buttonColors(containerColor = C.Blue)
                    ) { Text("CONNECT") }
                    OutlinedButton(onClick = { vm.stop() }) { Text("DISCONNECT", color = C.Text) }
                }
            }
        }
        Panel(Modifier.weight(1f).fillMaxHeight()) {
            Column(Modifier.fillMaxSize().padding(u(12)), verticalArrangement = Arrangement.spacedBy(u(10))) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("DEMO MODE", color = C.Text, fontSize = t(12))
                        Text("Simulated values, to test the screens without the car.", color = C.Dim, fontSize = t(10))
                    }
                    Switch(checked = demo, onCheckedChange = { vm.setDemo(it) })
                }
                Text("HOW TO CONNECT", color = C.Dim, fontSize = t(10))
                Text(
                    "1. Ignition ON (or engine running).\n" +
                        "2. Phone Wi-Fi → WIFI_ENET. Accept \"no internet\" and stay connected.\n" +
                        "3. Close BimmerLink – only one app can talk to the car.\n" +
                        "4. The app finds the car automatically (UDP 6811 → TCP 6801).",
                    color = C.Text, fontSize = t(10)
                )
            }
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = C.Text, unfocusedTextColor = C.Text,
    focusedBorderColor = C.Blue, unfocusedBorderColor = C.Border,
    focusedLabelColor = C.Blue, unfocusedLabelColor = C.Dim,
    cursorColor = C.Blue,
)

// ======================================================================= SENSORS

@Composable
fun SensorsScreen(vm: DashViewModel) {
    val sources by vm.sources.collectAsState()
    val values by vm.values.collectAsState()
    val raw by vm.raw.collectAsState()
    val unsupported by vm.unsupported.collectAsState()
    val scope = rememberCoroutineScope()

    var testEcu by remember { mutableStateOf("12") }
    var testUds by remember { mutableStateOf("22 F4 0C") }
    var testResult by remember { mutableStateOf("") }
    var diag by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current

    diag?.let { report ->
        AlertDialog(
            onDismissRequest = { diag = null },
            title = { Text("Diagnostic") },
            text = {
                SelectionContainer {
                    Text(
                        report, fontFamily = FontFamily.Monospace, fontSize = t(10),
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    )
                }
            },
            confirmButton = { TextButton(onClick = { clipboard.setText(AnnotatedString(report)) }) { Text("COPY") } },
            dismissButton = { TextButton(onClick = { diag = null }) { Text("CLOSE") } }
        )
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(u(6))) {
        Panel(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(u(8)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(u(8))
            ) {
                Text("TEST", color = C.Dim, fontSize = t(10))
                OutlinedTextField(testEcu, { testEcu = it }, label = { Text("ECU") }, singleLine = true,
                    colors = fieldColors(), modifier = Modifier.width(u(80)))
                OutlinedTextField(testUds, { testUds = it }, label = { Text("UDS hex") }, singleLine = true,
                    colors = fieldColors(), modifier = Modifier.width(u(170)))
                Button(
                    onClick = {
                        scope.launch {
                            testResult = vm.rawRequest(testEcu, testUds)
                                .fold({ it }, { "Error: ${it.message}" })
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = C.Blue)
                ) { Text("SEND") }
                Text(testResult, color = C.Text, fontSize = t(11), fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f), maxLines = 2)
                Button(
                    onClick = {
                        diag = "Running…"
                        scope.launch { diag = vm.runDiagnostic() }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = C.Amber)
                ) { Text("DIAGNOSTIC", color = C.Bg) }
            }
        }
        Panel(Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = u(10), vertical = u(4))) {
                item {
                    Text(
                        "Source: ECU DID BYTES FACTOR OFFSET [s]   BMW DID e.g. 12 586F 2 0.001 -1   OBD PID e.g. 12 P0C 2 0.25 0   (empty = off)",
                        color = C.Dim, fontSize = t(9), modifier = Modifier.padding(vertical = u(4))
                    )
                }
                items(SENSORS.filter { !it.derived }) { def ->
                    var text by remember(sources[def.id]) { mutableStateOf(sources[def.id]?.format() ?: "") }
                    var bad by remember { mutableStateOf(false) }
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = u(3)),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(u(8))
                    ) {
                        Text(def.label, color = C.Text, fontSize = t(10), modifier = Modifier.width(u(120)))
                        Text(
                            if (def.id in unsupported) "N/A" else "${def.formatValue(values[def.id])} ${def.unit}",
                            color = if (def.id in unsupported) C.Red else C.Text,
                            fontSize = t(11), fontWeight = FontWeight.Bold, modifier = Modifier.width(u(90))
                        )
                        OutlinedTextField(
                            text, { text = it; bad = false }, singleLine = true, isError = bad,
                            colors = fieldColors(), modifier = Modifier.width(u(200))
                        )
                        TextButton(onClick = { bad = !vm.saveSource(def.id, text) }) { Text("SAVE") }
                        TextButton(onClick = { vm.resetSource(def.id) }) { Text("DEFAULT", color = C.Dim) }
                        Text(raw[def.id] ?: "", color = C.Dim, fontSize = t(8), fontFamily = FontFamily.Monospace,
                            maxLines = 1, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
