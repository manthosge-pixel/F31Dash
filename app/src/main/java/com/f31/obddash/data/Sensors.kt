package com.f31.obddash.data

import android.content.Context
import java.util.Locale

enum class SensorId {
    RPM, BOOST, GEAR, WATER_TEMP, OIL_TEMP, INTAKE_TEMP, MAP, BARO, THROTTLE, BATTERY,
    SPEED, FUEL_TRIM, AFR, OIL_PRESSURE, TRANS_TEMP, RUN_TIME
}

/**
 * Where a value comes from: UDS ReadDataByIdentifier (0x22) on [ecu] for [did].
 * value = raw(bytes, big-endian, optionally signed) * factor + offset
 * Text form: "ECU DID BYTES FACTOR OFFSET [s]"  e.g. "12 F40C 2 0.25 0"
 */
data class DidSource(
    val ecu: Int,
    val did: Int,
    val bytes: Int,
    val factor: Double,
    val offset: Double,
    val signed: Boolean = false,
) {
    fun decode(resp: ByteArray): Double? {
        if (resp.size < 3 + bytes) return null
        if ((resp[0].toInt() and 0xFF) != 0x62) return null
        var raw = 0L
        for (i in 0 until bytes) raw = (raw shl 8) or (resp[3 + i].toLong() and 0xFF)
        if (signed) {
            val bits = bytes * 8
            if (raw and (1L shl (bits - 1)) != 0L) raw -= (1L shl bits)
        }
        return raw * factor + offset
    }

    fun format(): String = buildString {
        append(String.format(Locale.US, "%02X %04X %d %s %s", ecu, did, bytes, factor.clean(), offset.clean()))
        if (signed) append(" s")
    }

    companion object {
        fun parse(s: String): DidSource? {
            val p = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (p.size < 5) return null
            return runCatching {
                DidSource(
                    p[0].toInt(16), p[1].toInt(16), p[2].toInt(),
                    p[3].toDouble(), p[4].toDouble(), p.getOrNull(5) == "s"
                )
            }.getOrNull()?.takeIf { it.bytes in 1..4 }
        }
    }
}

private fun Double.clean(): String =
    if (this == Math.floor(this) && kotlin.math.abs(this) < 1e12) toLong().toString() else toString()

data class SensorDef(
    val id: SensorId,
    val label: String,
    val unit: String,
    val min: Double,
    val max: Double,
    val midLabel: String,
    val decimals: Int,
    val fast: Boolean,
    val default: DidSource?,
    val derived: Boolean = false,
    val warnAbove: Double? = null,
    val warnBelow: Double? = null,
)

private const val DME = 0x12

/**
 * Defaults use the standard OBD values that the DME exposes as UDS DIDs 0xF4xx
 * (F4 + OBD PID). BMW-only values (gear, oil pressure, gearbox temp) have no public
 * standard DID — set them in the SENSORS screen once you find the right DID.
 */
val SENSORS: List<SensorDef> = listOf(
    SensorDef(SensorId.RPM, "RPM", "rpm", 0.0, 7000.0, "3500", 0, true, DidSource(DME, 0xF40C, 2, 0.25, 0.0), warnAbove = 5000.0),
    SensorDef(SensorId.BOOST, "BOOST", "bar", -1.0, 2.0, "0.5", 2, true, null, derived = true, warnAbove = 1.0),
    SensorDef(SensorId.GEAR, "GEAR", "", 0.0, 8.0, "", 0, true, null),
    SensorDef(SensorId.WATER_TEMP, "WATER TEMP", "°C", 50.0, 130.0, "90", 0, false, DidSource(DME, 0xF405, 1, 1.0, -40.0), warnAbove = 112.0),
    SensorDef(SensorId.OIL_TEMP, "OIL TEMP", "°C", 50.0, 150.0, "100", 0, false, DidSource(DME, 0xF45C, 1, 1.0, -40.0), warnAbove = 130.0),
    SensorDef(SensorId.INTAKE_TEMP, "INTAKE AIR TEMP", "°C", -20.0, 80.0, "20", 0, false, DidSource(DME, 0xF40F, 1, 1.0, -40.0), warnAbove = 60.0),
    SensorDef(SensorId.MAP, "MAP / BOOST", "kPa", 0.0, 255.0, "MAP ABS", 0, true, DidSource(DME, 0xF40B, 1, 1.0, 0.0)),
    SensorDef(SensorId.BARO, "BARO", "kPa", 80.0, 110.0, "", 0, false, DidSource(DME, 0xF433, 1, 1.0, 0.0)),
    SensorDef(SensorId.THROTTLE, "THROTTLE", "%", 0.0, 100.0, "50", 0, true, DidSource(DME, 0xF411, 1, 100.0 / 255.0, 0.0)),
    SensorDef(SensorId.BATTERY, "BATTERY", "V", 11.0, 16.0, "13", 1, false, DidSource(DME, 0xF442, 2, 0.001, 0.0), warnBelow = 12.0),
    SensorDef(SensorId.SPEED, "SPEED", "km/h", 0.0, 240.0, "120", 0, true, DidSource(DME, 0xF40D, 1, 1.0, 0.0)),
    SensorDef(SensorId.FUEL_TRIM, "FUEL TRIM ST", "%", -25.0, 25.0, "0", 1, true, DidSource(DME, 0xF406, 1, 100.0 / 128.0, -100.0)),
    SensorDef(SensorId.AFR, "AIR-FUEL RATIO", ":1", 10.0, 18.0, "14", 1, true, DidSource(DME, 0xF424, 2, 14.7 * 2.0 / 65536.0, 0.0)),
    SensorDef(SensorId.OIL_PRESSURE, "OIL PRESSURE", "bar", 0.0, 7.0, "4", 1, true, null, warnBelow = 1.0),
    SensorDef(SensorId.TRANS_TEMP, "TRANSMISSION TEMP", "°C", 40.0, 140.0, "90", 0, false, null, warnAbove = 120.0),
    SensorDef(SensorId.RUN_TIME, "ENGINE RUN TIME", "h:min:s", 0.0, 0.0, "", 0, false, DidSource(DME, 0xF41F, 2, 1.0, 0.0)),
)

val SENSOR_MAP: Map<SensorId, SensorDef> = SENSORS.associateBy { it.id }

fun SensorDef.formatValue(v: Double?): String {
    if (v == null) return "--"
    return when (id) {
        SensorId.RUN_TIME -> {
            val s = v.toLong()
            String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
        }
        SensorId.GEAR -> if (v.toInt() == 0) "N" else v.toInt().toString()
        else -> String.format(Locale.US, "%.${decimals}f", v)
    }
}

class AppPrefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("f31dash", Context.MODE_PRIVATE)

    var manualIp: String
        get() = sp.getString("manual_ip", "") ?: ""
        set(v) = sp.edit().putString("manual_ip", v).apply()

    var demo: Boolean
        get() = sp.getBoolean("demo", false)
        set(v) = sp.edit().putBoolean("demo", v).apply()

    fun loadSources(): Map<SensorId, DidSource> =
        SENSORS.filter { !it.derived }.mapNotNull { def ->
            val stored = sp.getString("src_" + def.id.name, null)
            val src = if (stored == null) def.default else DidSource.parse(stored)
            src?.let { def.id to it }
        }.toMap()

    /** Empty text = sensor disabled. */
    fun saveSource(id: SensorId, text: String) {
        sp.edit().putString("src_" + id.name, text.trim()).apply()
    }

    fun resetSource(id: SensorId) {
        sp.edit().remove("src_" + id.name).apply()
    }
}
