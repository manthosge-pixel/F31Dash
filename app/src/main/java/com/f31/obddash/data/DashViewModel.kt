package com.f31.obddash.data

import android.app.Application
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
    private var job: Job? = null

    // ---------------------------------------------------------------- control

    fun start() {
        if (job?.isActive == true) return
        job = viewModelScope.launch(Dispatchers.IO) {
            if (_demo.value) runDemo() else runEnet()
        }
    }

    fun stop() {
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
        client?.close()
        super.onCleared()
    }

    // ---------------------------------------------------------------- ENET

    private suspend fun runEnet() {
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
                pollLoop(c)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _status.value = ConnStatus(ConnState.ERROR, e.message ?: e.javaClass.simpleName)
            } finally {
                client?.close()
                client = null
            }
            delay(3000)
        }
    }

    private suspend fun pollLoop(c: HsfzClient) {
        var cycle = 0
        val fails = HashMap<SensorId, Int>()
        var reqCount = 0
        var rateT0 = System.currentTimeMillis()

        while (currentCoroutineContext().isActive) {
            val cfg = _sources.value
            val vals = HashMap(_values.value)
            var asked = 0
            var timeouts = 0

            for (def in SENSORS) {
                if (def.derived) continue
                if (!def.fast && cycle % 8 != 0) continue
                if (def.id in _unsupported.value) continue
                val src = cfg[def.id] ?: continue

                asked++
                val resp = try {
                    c.request(src.ecu, byteArrayOf(0x22, (src.did shr 8).toByte(), src.did.toByte()))
                } catch (e: SocketTimeoutException) {
                    timeouts++
                    _raw.update { it + (def.id to "timeout") }
                    continue
                }
                reqCount++
                _raw.update { it + (def.id to resp.toHex()) }

                val v = src.decode(resp)
                if (v == null) {
                    val n = (fails[def.id] ?: 0) + 1
                    fails[def.id] = n
                    if (n >= 3) _unsupported.update { it + def.id }
                    vals.remove(def.id)
                } else {
                    fails[def.id] = 0
                    vals[def.id] = v
                }
            }

            if (asked > 0 && timeouts == asked) throw IOException("Car not responding")

            deriveBoost(vals)
            publish(vals)
            cycle++

            val now = System.currentTimeMillis()
            if (now - rateT0 >= 2000) {
                _pollRate.value = reqCount * 1000.0 / (now - rateT0)
                reqCount = 0; rateT0 = now
            }
            if (asked == 0) delay(200)
        }
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
        sb.appendLine("App 0.2 · status: ${st.state} · ${st.detail}")
        sb.appendLine("Unsupported: ${_unsupported.value.joinToString().ifEmpty { "-" }}")
        val c = client
        if (c == null || !c.isConnected) {
            sb.appendLine("No TCP connection to the car.")
            return@withContext sb.toString()
        }
        val tests = listOf(
            0x12 to "3E 00",        // tester present
            0x12 to "22 F1 90",     // VIN
            0x12 to "22 F4 0C",     // RPM (OBD DID)
            0x12 to "22 F4 05",     // coolant (OBD DID)
            0x12 to "22 F4 00",     // supported PIDs 01-20
            0x12 to "22 F1 8C",     // ECU serial
            0x10 to "22 F1 90",     // gateway VIN
            0x40 to "22 F1 90",     // FEM/BDC VIN
            0x18 to "22 F1 90",     // gearbox VIN
        )
        for ((ecu, req) in tests) {
            val line = try {
                c.request(ecu, req.hexToBytes(), 2500).toHex()
            } catch (e: Exception) {
                "ERR ${e.javaClass.simpleName}: ${e.message}"
            }
            sb.appendLine(String.format("%02X  %-9s → %s", ecu, req, line))
        }
        sb.appendLine("--- last sensor answers ---")
        _raw.value.forEach { (id, r) -> sb.appendLine("$id: $r") }
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
            deriveBoost(vals)
            publish(vals)
            delay(50)
        }
    }
}
