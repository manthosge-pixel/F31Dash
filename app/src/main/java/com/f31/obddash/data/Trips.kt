package com.f31.obddash.data

import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Summary of one drive, saved next to its CSV as trip_*.json. */
data class TripSummary(
    val name: String,          // file base name, e.g. trip_20261024_103512
    val startMs: Long,
    val durationS: Long,
    val distanceKm: Double,
    val fuelL: Double,
    val maxRpm: Double,
    val maxBoost: Double,
    val maxSpeed: Double,
    val maxOilTemp: Double,
    val maxWaterTemp: Double,
    // health markers (null = condition not met during this drive)
    val hotIdleOilPressure: Double?,   // bar, oil > 80 °C, standstill, idle
    val warmFuelTrim: Double?,         // %, average short-term trim with warm engine
    val warmupS: Long?,                // seconds for coolant to reach 80 °C from a cold start
) {
    val avgL100: Double? get() = if (distanceKm > 0.5) fuelL / distanceKm * 100.0 else null

    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name); put("startMs", startMs); put("durationS", durationS)
        put("distanceKm", distanceKm); put("fuelL", fuelL)
        put("maxRpm", maxRpm); put("maxBoost", maxBoost); put("maxSpeed", maxSpeed)
        put("maxOilTemp", maxOilTemp); put("maxWaterTemp", maxWaterTemp)
        hotIdleOilPressure?.let { put("hotIdleOilPressure", it) }
        warmFuelTrim?.let { put("warmFuelTrim", it) }
        warmupS?.let { put("warmupS", it) }
    }

    companion object {
        fun fromJson(o: JSONObject) = TripSummary(
            o.getString("name"), o.getLong("startMs"), o.getLong("durationS"),
            o.getDouble("distanceKm"), o.getDouble("fuelL"),
            o.optDouble("maxRpm", 0.0), o.optDouble("maxBoost", 0.0), o.optDouble("maxSpeed", 0.0),
            o.optDouble("maxOilTemp", 0.0), o.optDouble("maxWaterTemp", 0.0),
            if (o.has("hotIdleOilPressure")) o.getDouble("hotIdleOilPressure") else null,
            if (o.has("warmFuelTrim")) o.getDouble("warmFuelTrim") else null,
            if (o.has("warmupS")) o.getLong("warmupS") else null,
        )
    }
}

/**
 * Records drives automatically: a trip starts when the engine runs (RPM > 400)
 * and ends after 30 s with the engine off, or when the connection is lost.
 * Writes one CSV row every 0.5 s.
 */
class TripRecorder(private val dir: File) {

    private val columns = listOf(
        SensorId.RPM, SensorId.SPEED, SensorId.GEAR, SensorId.THROTTLE, SensorId.BOOST, SensorId.MAP,
        SensorId.AFR, SensorId.MAF, SensorId.FUEL_RATE, SensorId.FUEL_TRIM, SensorId.WATER_TEMP,
        SensorId.OIL_TEMP, SensorId.OIL_PRESSURE, SensorId.INTAKE_TEMP, SensorId.TRANS_TEMP, SensorId.BATTERY,
    )

    private var writer: BufferedWriter? = null
    private var name = ""
    private var startMs = 0L
    private var lastMs = 0L
    private var lastRowMs = 0L
    private var lastRunningMs = 0L
    private var distanceKm = 0.0
    private var fuelL = 0.0
    private var maxRpm = 0.0; private var maxBoost = 0.0; private var maxSpeed = 0.0
    private var maxOil = 0.0; private var maxWater = 0.0
    private var idleOpSum = 0.0; private var idleOpN = 0
    private var trimSum = 0.0; private var trimN = 0
    private var coldStart = false
    private var warmupS: Long? = null

    val active: Boolean get() = writer != null

    init { dir.mkdirs() }

    /** Feed every polling cycle. Returns a summary when a trip just ended. */
    fun onSample(v: Map<SensorId, Double>, now: Long = System.currentTimeMillis()): TripSummary? {
        val rpm = v[SensorId.RPM] ?: 0.0
        val running = rpm > 400.0
        if (running) lastRunningMs = now

        if (writer == null) {
            if (running) begin(v, now)
            return null
        }
        if (!running && now - lastRunningMs > 30_000) return finish(now)

        val dtH = ((now - lastMs).coerceIn(0, 5_000)) / 3_600_000.0
        lastMs = now
        val speed = v[SensorId.SPEED] ?: 0.0
        distanceKm += speed * dtH
        fuelL += (v[SensorId.FUEL_RATE] ?: 0.0) * dtH

        maxRpm = maxOf(maxRpm, rpm)
        maxSpeed = maxOf(maxSpeed, speed)
        v[SensorId.BOOST]?.let { maxBoost = maxOf(maxBoost, it) }
        v[SensorId.OIL_TEMP]?.let { maxOil = maxOf(maxOil, it) }
        v[SensorId.WATER_TEMP]?.let { maxWater = maxOf(maxWater, it) }

        val oilT = v[SensorId.OIL_TEMP]
        val water = v[SensorId.WATER_TEMP]
        val op = v[SensorId.OIL_PRESSURE]
        if (running && rpm < 950 && speed < 1 && oilT != null && oilT > 80 && op != null) {
            idleOpSum += op; idleOpN++
        }
        val trim = v[SensorId.FUEL_TRIM]
        if (running && water != null && water > 80 && trim != null) {
            trimSum += trim; trimN++
        }
        if (coldStart && warmupS == null && water != null && water >= 80) {
            warmupS = (now - startMs) / 1000
        }

        if (now - lastRowMs >= 500) {
            lastRowMs = now
            val ts = (now - startMs) / 1000.0
            val row = buildString {
                append(String.format(Locale.US, "%.1f", ts))
                columns.forEach { id ->
                    append(',')
                    v[id]?.let { append(String.format(Locale.US, "%.3f", it)) }
                }
            }
            writer?.apply { write(row); newLine() }
        }
        return null
    }

    /** Connection lost / app closing: close the current trip. */
    fun end(now: Long = System.currentTimeMillis()): TripSummary? = if (writer != null) finish(now) else null

    private fun begin(v: Map<SensorId, Double>, now: Long) {
        name = "trip_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now))
        writer = File(dir, "$name.csv").bufferedWriter().apply {
            write("t_s," + columns.joinToString(",") { it.name.lowercase() }); newLine()
        }
        startMs = now; lastMs = now; lastRowMs = 0; lastRunningMs = now
        distanceKm = 0.0; fuelL = 0.0
        maxRpm = 0.0; maxBoost = 0.0; maxSpeed = 0.0; maxOil = 0.0; maxWater = 0.0
        idleOpSum = 0.0; idleOpN = 0; trimSum = 0.0; trimN = 0
        coldStart = (v[SensorId.WATER_TEMP] ?: 100.0) < 60.0
        warmupS = null
    }

    private fun finish(now: Long): TripSummary? {
        try { writer?.close() } catch (_: Exception) {}
        writer = null
        val endMs = minOf(now, lastRunningMs + 1000)
        val s = TripSummary(
            name, startMs, (endMs - startMs) / 1000, distanceKm, fuelL,
            maxRpm, maxBoost, maxSpeed, maxOil, maxWater,
            if (idleOpN >= 10) idleOpSum / idleOpN else null,
            if (trimN >= 20) trimSum / trimN else null,
            warmupS,
        )
        // drop tiny "trips" (engine started for a few seconds)
        if (s.durationS < 20) {
            File(dir, "$name.csv").delete()
            return null
        }
        File(dir, "$name.json").writeText(s.toJson().toString())
        return s
    }

    companion object {
        fun loadAll(dir: File): List<TripSummary> =
            (dir.listFiles { f -> f.name.startsWith("trip_") && f.name.endsWith(".json") } ?: emptyArray())
                .mapNotNull { runCatching { TripSummary.fromJson(JSONObject(it.readText())) }.getOrNull() }
                .sortedByDescending { it.startMs }

        fun delete(dir: File, name: String) {
            File(dir, "$name.csv").delete()
            File(dir, "$name.json").delete()
        }
    }
}
