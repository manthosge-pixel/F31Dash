package com.f31.obddash.data

import android.app.Application
import android.net.wifi.WifiManager
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.f31.obddash.enet.EnetDiscovery
import com.f31.obddash.enet.HsfzClient
import com.f31.obddash.enet.NetworkUtil
import com.f31.obddash.enet.hexToBytes
import com.f31.obddash.enet.toHex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.math.max
import kotlin.math.sin

enum class ConnState { DISCONNECTED, SEARCHING, CONNECTING, CONNECTED, ERROR, DEMO }

data class ConnStatus(val state: ConnState, val detail: String)

enum class PerfPhase { WAIT_STOP, READY, RUNNING }

data class PerfState(
    val phase: PerfPhase = PerfPhase.WAIT_STOP,
    val startMs: Long = 0,
    val lastTime: Double? = null,
    val best: Double? = null,
)

data class Dtc(val code: Int, val status: Int) {
    val codeHex: String get() = "%06X".format(code)
    val statusHex: String get() = "%02X".format(status)
    val active: Boolean get() = status and 0x01 != 0
}

private const val DYN_DID_LO: Byte = 0x03   // dynamic DID F303

val HISTORY_IDS = listOf(SensorId.RPM, SensorId.BOOST, SensorId.SPEED, SensorId.WATER_TEMP, SensorId.OIL_TEMP, SensorId.AFR)
private const val HISTORY_LEN = 300

class DashViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = AppPrefs(app)

    private val _sources = MutableStateFlow(prefs.loadSources())
    val sources: StateFlow<Map<SensorId, DidSource>> = _sources.asStateFlow()

    private val _manualIp = MutableStateFlow(prefs.manualIp)
    val manualIp: StateFlow<String> = _manualIp.asStateFlow()

    private val _demo = MutableStateFlow(prefs.demo)
    val demo: StateFlow<Boolean> = _demo.asStateFlow()

    private val _values = MutableStateFlow<Map<SensorId, Double>>(emptyMap())
    val values: StateFlow<Map<SensorId, Double>> = _values.asStateFlow()

    private val _raw = MutableStateFlow<Map<SensorId, String>>(emptyMap())
    val raw: StateFlow<Map<SensorId, String>> = _raw.asStateFlow()

    private val _unsupported = MutableStateFlow<Set<SensorId>>(emptySet())
    val unsupported: StateFlow<Set<SensorId>> = _unsupported.asStateFlow()

    private val _status = MutableStateFlow(ConnStatus(ConnState.DISCONNECTED, "BMW ENET Wi-Fi"))
    val status: StateFlow<ConnStatus> = _status.asStateFlow()

    private val _history = MutableStateFlow<Map<SensorId, List<Float>>>(emptyMap())
    val history: StateFlow<Map<SensorId, List<Float>>> = _history.asStateFlow()

    private val _perf = MutableStateFlow(PerfState())
    val perf: StateFlow<PerfState> = _perf.asStateFlow()

    private val _pollRate = MutableStateFlow(0.0)
    val pollRate: StateFlow<Double> = _pollRate.asStateFlow()

    @Volatile private var client: HsfzClient? = null
    /** Where OBD Mode 01 requests go: the DME itself (12) or the OBD functional address (DF). */
    @Volatile private var obdTarget: Int = HsfzClient.ECU_DME

    private fun detectObdTarget(c: HsfzClient): Int {
        // The DME answers Mode 01 directly; DF only as a last resort (other modules answer there too).
        for (t in listOf(HsfzClient.ECU_DME, HsfzClient.ECU_DME, HsfzClient.ECU_DME, 0xDF)) {
            val ok = try {
                val r = c.request(t, byteArrayOf(0x01, 0x00), 2000)
                (r[0].toInt() and 0xFF) == 0x41
            } catch (e: SocketTimeoutException) { false }
            if (ok) return t
        }
        return HsfzClient.ECU_DME
    }
    private var job: Job? = null

    // ---------------------------------------------------------------- control

    private var wifiLock: WifiManager.WifiLock? = null

    @Suppress("DEPRECATION")
    private fun lockWifi(on: Boolean) {
        try {
            if (on) {
                if (wifiLock?.isHeld == true) return
                val wm = getApplication<Application>().getSystemService(WifiManager::class.java) ?: return
                val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                           else WifiManager.WIFI_MODE_FULL_HIGH_PERF
                wifiLock = wm.createWifiLock(mode, "f31dash").apply { setReferenceCounted(false); acquire() }
            } else {
                wifiLock?.release(); wifiLock = null
            }
        } catch (_: Exception) {}
    }

    fun start() {
        if (job?.isActive == true) return
        job = viewModelScope.launch(Dispatchers.IO) {
            if (_demo.value) runDemo() else runEnet()
        }
    }

    fun stop() {
        lockWifi(false)
        job?.cancel()
        job = null
        client?.close()
        client = null
        _values.value = emptyMap()
        _status.value = ConnStatus(ConnState.DISCONNECTED, "BMW ENET Wi-Fi")
    }

    fun restart() {
        stop(); start()
    }

    fun setDemo(on: Boolean) {
        prefs.demo = on; _demo.value = on; restart()
    }

    fun setManualIp(ip: String) {
        prefs.manualIp = ip.trim(); _manualIp.value = ip.trim()
    }

    fun saveSource(id: SensorId, text: String): Boolean {
        if (text.isNotBlank() && DidSource.parse(text) == null) return false
        prefs.saveSource(id, text)
        _sources.value = prefs.loadSources()
        _unsupported.update { it - id }
        return true
    }

    fun resetSource(id: SensorId) {
        prefs.resetSource(id)
        _sources.value = prefs.loadSources()
        _unsupported.update { it - id }
    }

    fun resetPerfBest() {
        _perf.update { it.copy(best = null, lastTime = null) }
    }

    override fun onCleared() {
        endTrip()
        lockWifi(false)
        client?.close()
        super.onCleared()
    }

    // ---------------------------------------------------------------- ENET

    private suspend fun runEnet() {
        lockWifi(true)
        while (currentCoroutineContext().isActive) {
            try {
                val net = NetworkUtil.findWifiNetwork(getApplication())
                _status.value = ConnStatus(ConnState.SEARCHING, "Searching for car…")
                var vin: String? = null
                val ip = _manualIp.value.ifBlank { null }
                    ?: EnetDiscovery.discover(net)?.also { vin = it.vin }?.ip
                    ?: throw IOException("Car not found. Ignition on and phone on WIFI_ENET?")

                _status.value = ConnStatus(ConnState.CONNECTING, ip)
                val c = HsfzClient(net)
                c.connect(ip)
                client = c
                _status.value = ConnStatus(ConnState.CONNECTED, ip + (vin?.let { " · $it" } ?: ""))
                _unsupported.value = emptySet()
                obdTarget = detectObdTarget(c)
                pollLoop(c)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _status.value = ConnStatus(ConnState.ERROR, e.message ?: e.javaClass.simpleName)
            } finally {
                endTrip()
                client?.close()
                client = null
            }
            delay(3000)
        }
    }

    @Volatile private var multiObdOk = true
    @Volatile private var dynOk = true
    @Volatile private var dynFails = 0
    private var dynDefined: List<Pair<SensorId, DidSource>>? = null
    @Volatile private var dynInfo = "-"

    /**
     * Defines dynamic DID F303 = the wanted bytes of several BMW DIDs (UDS 2C 01).
     * Lives only in the ECU's RAM; cleared with 2C 03 and lost at ignition off.
     */
    private fun defineDynamic(c: HsfzClient, srcs: List<DidSource>): Boolean {
        return try {
            c.request(HsfzClient.ECU_DME, byteArrayOf(0x2C, 0x03, 0xF3.toByte(), DYN_DID_LO), 1000)
            val req = ArrayList<Byte>()
            req += listOf<Byte>(0x2C, 0x01, 0xF3.toByte(), DYN_DID_LO)
            for (s in srcs) {
                req += (s.did shr 8).toByte(); req += s.did.toByte()
                req += (s.pos + 1).toByte()          // position, 1-based
                req += s.bytes.toByte()              // size
            }
            val r = c.request(HsfzClient.ECU_DME, req.toByteArray(), 1500)
            dynInfo = "define → " + r.toHex()
            (r[0].toInt() and 0xFF) == 0x6C
        } catch (e: Exception) {
            dynInfo = "define → " + (e.message ?: e.javaClass.simpleName)
            false
        }
    }

    /**
     * One cycle = few requests: all fast OBD PIDs in one Mode 01 request (up to 6 PIDs),
     * the BMW DIDs of the DME in one 22 request, the gear every 3rd cycle,
     * slow values (temperatures, battery…) only every 6th cycle.
     */
    private suspend fun pollLoop(c: HsfzClient) {
        var cycle = 0
        val fails = HashMap<SensorId, Int>()
        var cycles = 0
        var rateT0 = System.currentTimeMillis()
        multiObdOk = true
        dynOk = true
        dynFails = 0
        dynDefined = null

        while (currentCoroutineContext().isActive) {
            val cfg = _sources.value
            val unsup = _unsupported.value
            val vals = HashMap(_values.value)
            val raws = HashMap<SensorId, String>()
            var asked = 0
            var timeouts = 0

            fun good(id: SensorId, v: Double, raw: String) {
                fails[id] = 0; vals[id] = v; raws[id] = raw
            }
            fun bad(id: SensorId, raw: String) {
                val n = (fails[id] ?: 0) + 1
                fails[id] = n
                if (n >= 3) _unsupported.update { it + id }
                vals.remove(id); raws[id] = raw
            }
            fun ask(target: Int, req: ByteArray): ByteArray? {
                asked++
                return try {
                    c.request(target, req, 700)
                } catch (e: SocketTimeoutException) {
                    timeouts++; null
                }
            }

            val due: List<Pair<SensorId, DidSource>> = SENSORS.filter { d ->
                !d.derived && d.id !in unsup && cfg[d.id] != null && when {
                    d.id == SensorId.GEAR -> cycle % 4 == 0
                    d.id == SensorId.TRANS_TEMP -> cycle % 20 == 5
                    d.fast -> true
                    else -> cycle % 6 == 2
                }
            }.map { it.id to cfg.getValue(it.id) }

            val singles = ArrayList<Pair<SensorId, DidSource>>()

            // ---- 1. OBD Mode 01, several PIDs per request
            val obdItems = due.filter { (_, s) -> s.obd && s.ecu == HsfzClient.ECU_DME && OBD_PID_LEN.containsKey(s.did) }
            if (multiObdOk && obdItems.size > 1) {
                for (chunk in obdItems.chunked(6)) {
                    val req = byteArrayOf(0x01) + ByteArray(chunk.size) { chunk[it].second.did.toByte() }
                    val resp = ask(obdTarget, req) ?: continue
                    val starts = splitObd(resp)
                    if (starts == null) { multiObdOk = false; singles += chunk; continue }
                    for ((id, src) in chunk) {
                        val st = starts[src.did]
                        val v = st?.let { src.decodeData(resp, it) }
                        if (v != null) good(id, v, "multi " + resp.toHex()) else bad(id, "no answer in " + resp.toHex())
                    }
                }
            } else singles += obdItems

            // ---- 2. BMW DIDs of the DME: all of them in one dynamic DID (2C → 22 F303)
            val didAll = SENSORS.mapNotNull { d ->
                val src = cfg[d.id]
                if (!d.derived && d.id !in unsup && src != null && !src.obd && src.ecu == HsfzClient.ECU_DME) d.id to src else null
            }
            if (dynOk && didAll.size > 1) {
                if (dynDefined != didAll) {
                    dynDefined = if (defineDynamic(c, didAll.map { it.second })) didAll else null
                    if (dynDefined == null) { dynFails++; if (dynFails >= 3) dynOk = false }
                }
                val resp = if (dynDefined != null) ask(HsfzClient.ECU_DME, byteArrayOf(0x22, 0xF3.toByte(), DYN_DID_LO)) else null
                if (resp != null && (resp[0].toInt() and 0xFF) == 0x62 && resp.size >= 3 + didAll.sumOf { it.second.bytes }) {
                    dynFails = 0
                    var off = 3
                    for ((id, src) in didAll) {
                        val v = src.copy(pos = 0).decodeData(resp, off)
                        if (v != null) good(id, v, "dyn " + resp.toHex()) else bad(id, resp.toHex())
                        off += src.bytes
                    }
                } else {
                    if (resp != null) dynInfo = "read → " + resp.toHex()
                    dynDefined = null
                    dynFails++
                    if (dynFails >= 3) dynOk = false
                    singles += due.filter { (_, s) -> !s.obd && s.ecu == HsfzClient.ECU_DME }
                }
            } else singles += due.filter { (_, s) -> !s.obd && s.ecu == HsfzClient.ECU_DME }

            // ---- 3. everything else, one by one
            singles += due.filter { (_, s) -> !(s.obd && s.ecu == HsfzClient.ECU_DME && OBD_PID_LEN.containsKey(s.did)) && !(!s.obd && s.ecu == HsfzClient.ECU_DME) }
            for ((id, src) in singles) {
                val target = if (src.obd && src.ecu == HsfzClient.ECU_DME) obdTarget else src.ecu
                val resp = ask(target, src.requestBytes())
                if (resp == null) { bad(id, "timeout"); continue }
                val v = src.decode(resp)
                if (v != null) good(id, v, resp.toHex()) else bad(id, resp.toHex())
            }

            if (asked > 0 && timeouts == asked) throw IOException("Car not responding")

            if (raws.isNotEmpty()) _raw.update { it + raws }
            deriveBoost(vals)
            deriveFuel(vals)
            publish(vals)
            record(vals)
            cycle++
            cycles++

            val now = System.currentTimeMillis()
            if (now - rateT0 >= 2000) {
                _pollRate.value = cycles * 1000.0 / (now - rateT0)
                cycles = 0; rateT0 = now
            }
            if (asked == 0) delay(200)
        }
    }

    /** Splits "41 PID data PID data…" → PID to data start index; null if it can't be split. */
    private fun splitObd(resp: ByteArray): Map<Int, Int>? {
        if (resp.isEmpty() || (resp[0].toInt() and 0xFF) != 0x41) return null
        val out = HashMap<Int, Int>()
        var i = 1
        while (i < resp.size) {
            val pid = resp[i].toInt() and 0xFF
            val len = OBD_PID_LEN[pid] ?: return null
            if (i + 1 + len > resp.size) return null
            out[pid] = i + 1
            i += 1 + len
        }
        return out
    }

    /** Checks "62 DID data DID data…" in request order; returns data start of each, or null. */
    private fun splitDids(resp: ByteArray, srcs: List<DidSource>): List<Int>? {
        if (resp.isEmpty() || (resp[0].toInt() and 0xFF) != 0x62) return null
        val out = ArrayList<Int>()
        var i = 1
        for (s in srcs) {
            if (i + 2 > resp.size) return null
            val did = ((resp[i].toInt() and 0xFF) shl 8) or (resp[i + 1].toInt() and 0xFF)
            if (did != s.did) return null
            out += i + 2
            i += 2 + s.pos + s.bytes
        }
        return if (i == resp.size) out else null
    }

    /**
     * Fuel from air: fuel g/s = MAF / AFR (measured; 14.7 if unknown),
     * petrol ≈ 745 g per litre. L/100 km only when moving.
     */
    private fun deriveFuel(vals: HashMap<SensorId, Double>) {
        val maf = vals[SensorId.MAF]
        if (maf == null) { vals.remove(SensorId.FUEL_RATE); vals.remove(SensorId.CONSUMPTION); return }
        val afr = (vals[SensorId.AFR] ?: 14.7).coerceIn(9.0, 30.0)
        val lh = maf / afr / 745.0 * 3600.0
        vals[SensorId.FUEL_RATE] = lh
        val speed = vals[SensorId.SPEED] ?: 0.0
        if (speed > 3.0) vals[SensorId.CONSUMPTION] = (lh / speed * 100.0).coerceAtMost(99.9)
        else vals.remove(SensorId.CONSUMPTION)
    }

    // ---------------------------------------------------------------- trips

    private val tripDir = java.io.File(app.filesDir, "trips")
    private val recorder = TripRecorder(tripDir)

    private val _trips = MutableStateFlow(TripRecorder.loadAll(tripDir))
    val trips: StateFlow<List<TripSummary>> = _trips.asStateFlow()

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    private fun record(vals: Map<SensorId, Double>) {
        val done = recorder.onSample(vals)
        _recording.value = recorder.active
        if (done != null) _trips.value = TripRecorder.loadAll(tripDir)
    }

    private fun endTrip() {
        if (recorder.end() != null) _trips.value = TripRecorder.loadAll(tripDir)
        _recording.value = false
    }

    fun tripCsv(name: String): java.io.File = java.io.File(tripDir, "$name.csv")

    fun deleteTrip(name: String) {
        TripRecorder.delete(tripDir, name)
        _trips.value = TripRecorder.loadAll(tripDir)
    }

    private fun deriveBoost(vals: HashMap<SensorId, Double>) {
        val map = vals[SensorId.MAP]
        if (map != null) {
            val baro = vals[SensorId.BARO] ?: 101.3
            vals[SensorId.BOOST] = (map - baro) / 100.0
        } else vals.remove(SensorId.BOOST)
    }

    private fun publish(vals: Map<SensorId, Double>) {
        _values.value = vals
        _history.update { old ->
            HISTORY_IDS.associateWith { id ->
                val list = old[id] ?: emptyList()
                val v = vals[id]?.toFloat() ?: Float.NaN
                (if (list.size >= HISTORY_LEN) list.drop(list.size - HISTORY_LEN + 1) else list) + v
            }
        }
        updatePerf(vals[SensorId.SPEED])
    }

    private fun updatePerf(speed: Double?) {
        if (speed == null) return
        val now = System.currentTimeMillis()
        _perf.update { p ->
            when (p.phase) {
                PerfPhase.WAIT_STOP -> if (speed <= 0.5) p.copy(phase = PerfPhase.READY) else p
                PerfPhase.READY -> if (speed > 0.5) p.copy(phase = PerfPhase.RUNNING, startMs = now) else p
                PerfPhase.RUNNING -> when {
                    speed >= 100.0 -> {
                        val t = (now - p.startMs) / 1000.0
                        p.copy(phase = PerfPhase.WAIT_STOP, lastTime = t, best = p.best?.let { minOf(it, t) } ?: t)
                    }
                    speed <= 0.5 -> p.copy(phase = PerfPhase.READY)
                    else -> p
                }
            }
        }
    }

    // ---------------------------------------------------------------- tools

    suspend fun readDtcs(ecu: Int): Result<List<Dtc>> = withContext(Dispatchers.IO) {
        runCatching {
            if (_demo.value) return@runCatching listOf(Dtc(0x120308, 0x2C), Dtc(0x480A07, 0x08))
            val c = client ?: error("Not connected to the car")
            val r = c.request(ecu, byteArrayOf(0x19, 0x02, 0x0C), 4000)
            if ((r[0].toInt() and 0xFF) == 0x7F) error("ECU rejected request (NRC %02X)".format(r[2].toInt() and 0xFF))
            val list = ArrayList<Dtc>()
            var i = 3
            while (i + 3 < r.size) {
                val code = ((r[i].toInt() and 0xFF) shl 16) or ((r[i + 1].toInt() and 0xFF) shl 8) or (r[i + 2].toInt() and 0xFF)
                list += Dtc(code, r[i + 3].toInt() and 0xFF)
                i += 4
            }
            list
        }
    }

    suspend fun clearDtcs(ecu: Int): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (_demo.value) return@runCatching
            val c = client ?: error("Not connected to the car")
            val r = c.request(ecu, byteArrayOf(0x14, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()), 6000)
            if ((r[0].toInt() and 0xFF) != 0x54) error("Clear failed: ${r.toHex()}")
        }
    }

    /** Free-form UDS request, used in SENSORS to look for BMW DIDs. */
    suspend fun rawRequest(ecuHex: String, udsHex: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val c = client ?: error("Not connected to the car")
            val ecu = ecuHex.trim().toInt(16)
            c.request(ecu, udsHex.hexToBytes(), 3000).toHex()
        }
    }

    /** Sends a fixed set of requests and returns a readable report (for screenshots). */
    suspend fun runDiagnostic(): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        val st = _status.value
        sb.appendLine("App 0.11 · status: ${st.state} · ${st.detail}")
        sb.appendLine("Unsupported: ${_unsupported.value.joinToString().ifEmpty { "-" }}")
        val c = client
        if (c == null || !c.isConnected) {
            sb.appendLine("No TCP connection to the car.")
            return@withContext sb.toString()
        }
        sb.appendLine("OBD target: %02X".format(obdTarget))
        sb.appendLine("Updates/s: %.1f · avg request: %.0f ms · multi-PID: %s".format(_pollRate.value, client?.avgLatencyMs ?: 0.0, multiObdOk))
        sb.appendLine("Dynamic DID F303: ok=%s defined=%s · %s".format(dynOk, dynDefined != null, dynInfo))
        sb.appendLine("Wi-Fi low-latency lock: %s".format(wifiLock?.isHeld == true))
        val tests = listOf(
            0x12 to "01 00",        // supported PIDs 01-20
            0x12 to "01 20",        // supported PIDs 21-40
            0x12 to "01 40",        // supported PIDs 41-60
            0x12 to "01 34",        // O2 sensor 1 lambda + current
            0x12 to "01 44",        // commanded lambda
            0x12 to "01 0B",        // MAP (OBD)
            0x12 to "22 44 02",     // BMW oil temp
            0x12 to "22 42 05",     // BMW charge pressure
            0x12 to "22 42 01",     // BMW ambient pressure
            0x12 to "22 58 6F",     // BMW oil pressure
            0x18 to "22 DA 2E",     // EGS engaged gear (byte 1)
            0x18 to "22 DA 12",     // EGS ATF temperature (raw)
        )
        for ((ecu, req) in tests) {
            val line = try {
                c.request(ecu, req.hexToBytes(), 2500).toHex()
            } catch (e: Exception) {
                "ERR ${e.javaClass.simpleName}: ${e.message}"
            }
            sb.appendLine(String.format("%02X  %-9s → %s", ecu, req, line))
        }
        sb.appendLine("--- sensors (raw → value) ---")
        _raw.value.forEach { (id, r) ->
            val def = SENSOR_MAP[id]
            sb.appendLine("$id: $r → ${def?.formatValue(_values.value[id]) ?: ""} ${def?.unit ?: ""}")
        }
        sb.appendLine("BOOST: ${SENSOR_MAP.getValue(SensorId.BOOST).formatValue(_values.value[SensorId.BOOST])} bar")
        _unsupported.value = emptySet()
        sb.toString()
    }

    // ---------------------------------------------------------------- demo

    private suspend fun runDemo() {
        _status.value = ConnStatus(ConnState.DEMO, "Simulated data")
        val t0 = System.currentTimeMillis()
        while (currentCoroutineContext().isActive) {
            val t = (System.currentTimeMillis() - t0) / 1000.0
            val rpm = (2600 + 2100 * sin(t * 0.7) + 500 * sin(t * 2.1)).coerceIn(750.0, 6600.0)
            val load = ((rpm - 750) / 5850).coerceIn(0.0, 1.0)
            val speed = max(0.0, 70 + 75 * sin(t * 0.15))
            val vals = HashMap<SensorId, Double>()
            vals[SensorId.RPM] = rpm
            vals[SensorId.MAP] = 95 + 120 * load
            vals[SensorId.BARO] = 101.0
            vals[SensorId.THROTTLE] = 100 * load
            vals[SensorId.SPEED] = speed
            vals[SensorId.GEAR] = when {
                speed < 3 -> 0.0; speed < 20 -> 1.0; speed < 40 -> 2.0; speed < 65 -> 3.0
                speed < 90 -> 4.0; speed < 115 -> 5.0; else -> 6.0
            }
            vals[SensorId.WATER_TEMP] = 92 + 3 * sin(t * 0.05)
            vals[SensorId.OIL_TEMP] = 104 + 5 * sin(t * 0.03)
            vals[SensorId.INTAKE_TEMP] = 32 + 6 * load
            vals[SensorId.BATTERY] = 14.2 + 0.1 * sin(t)
            vals[SensorId.FUEL_TRIM] = 3 * sin(t * 1.3)
            vals[SensorId.AFR] = 14.7 - 2.5 * load + 0.2 * sin(t * 3)
            vals[SensorId.OIL_PRESSURE] = 1.5 + 3.0 * load
            vals[SensorId.TRANS_TEMP] = 82 + 4 * sin(t * 0.04)
            vals[SensorId.RUN_TIME] = t
            vals[SensorId.MAF] = 3.0 + 70.0 * load
            deriveBoost(vals)
            deriveFuel(vals)
            publish(vals)
            delay(50)
        }
    }
}
