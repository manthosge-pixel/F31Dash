package com.f31.obddash.data

import android.content.Context
import java.util.Locale

enum class SensorId {
    RPM, BOOST, GEAR, WATER_TEMP, OIL_TEMP, INTAKE_TEMP, MAP, BARO, THROTTLE, BATTERY,
    SPEED, FUEL_TRIM, AFR, OIL_PRESSURE, TRANS_TEMP, RUN_TIME
}

/**
 * Where a value comes from.
 *  - obd = false: UDS ReadDataByIdentifier  22 <DID hi> <DID lo>  → 62 DID data…
 *  - obd = true : OBD Mode 01               01 <PID>              → 41 PID data…
 * value = raw(bytes, big-endian, optionally signed) * factor + offset
 * Text form: "ECU DID BYTES FACTOR OFFSET [s]"
 *   e.g. "12 P0C 2 0.25 0"   (OBD PID 0C = RPM)
 *        "12 586F 2 0.001 -1" (BMW DID 586F)
 */
data class DidSource(
    val ecu: Int,
    val did: Int,
    val bytes: Int,
    val factor: Double,
    val offset: Double,
    val signed: Boolean = false,
    val obd: Boolean = false,
    /** Byte position inside the answer data (DID "DA2E@1" = second byte). */
    val pos: Int = 0,
) {
    fun requestBytes(): ByteArray =
        if (obd) byteArrayOf(0x01, did.toByte())
        else byteArrayOf(0x22, (did shr 8).toByte(), did.toByte())

    fun decode(resp: ByteArray): Double? {
        if (resp.size < 2) return null
        if (obd) {
            if ((resp[0].toInt() and 0xFF) != 0x41 || (resp[1].toInt() and 0xFF) != did) return null
        } else {
            if ((resp[0].toInt() and 0xFF) != 0x62) return null
        }
        return decodeData(resp, if (obd) 2 else 3)
    }

    /** Decodes this value from [buf], where this PID's / DID's data begins at [dataStart]. */
    fun decodeData(buf: ByteArray, dataStart: Int): Double? {
        val start = dataStart + pos
        if (start < 0 || buf.size < start + bytes) return null
        var raw = 0L
        for (i in 0 until bytes) raw = (raw shl 8) or (buf[start + i].toLong() and 0xFF)
        if (signed) {
            val bits = bytes * 8
            if (raw and (1L shl (bits - 1)) != 0L) raw -= (1L shl bits)
        }
        return raw * factor + offset
    }

    fun format(): String = buildString {
        val d = (if (obd) String.format(Locale.US, "P%02X", did) else String.format(Locale.US, "%04X", did)) +
            (if (pos > 0) "@$pos" else "")
        append(String.format(Locale.US, "%02X %s %d %s %s", ecu, d, bytes, factor.clean(), offset.clean()))
        if (signed) append(" s")
    }

    companion object {
        fun parse(s: String): DidSource? {
            val p = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (p.size < 5) return null
            return runCatching {
                val didPart = p[1].substringBefore("@")
                val pos = p[1].substringAfter("@", "0").toInt()
                val obd = didPart.startsWith("P", ignoreCase = true)
                val did = (if (obd) didPart.substring(1) else didPart).toInt(16)
                DidSource(
                    p[0].toInt(16), did, p[2].toInt(),
                    p[3].toDouble(), p[4].toDouble(), p.getOrNull(5) == "s", obd, pos
                )
            }.getOrNull()?.takeIf { it.bytes in 1..4 && it.pos in 0..64 && (!it.obd || it.did in 0..0xFF) }
        }
    }
}

/** Data length of standard OBD Mode 01 PIDs (needed to split multi-PID answers). */
val OBD_PID_LEN: Map<Int, Int> = mapOf(
    0x04 to 1, 0x05 to 1, 0x06 to 1, 0x07 to 1, 0x0B to 1, 0x0C to 2, 0x0D to 1, 0x0E to 1,
    0x0F to 1, 0x10 to 2, 0x11 to 1, 0x1F to 2, 0x24 to 4, 0x33 to 1, 0x34 to 4, 0x3C to 2,
    0x42 to 2, 0x44 to 2, 0x46 to 1, 0x5C to 1,
)

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
private const val EGS = 0x18

private fun obd(pid: Int, bytes: Int, factor: Double, offset: Double) =
    DidSource(DME, pid, bytes, factor, offset, obd = true)

/**
 * Defaults: standard OBD Mode 01 PIDs (the DME rejects the UDS F4xx form: 7F 22 31).
 * Oil pressure uses BMW DID 586F (absolute mbar, verified on an N55 MEVD17.2; -1 bar ≈ gauge).
 * Gear: EGS (18) DID DA2E byte 1 = engaged gear 1..8 (verified on an F10 ZF 8HP); 0xFF at standstill.
 * Gearbox temp: EGS DID DA12, 1 byte — scale not confirmed (raw − 40 is a guess).
 */
val SENSORS: List<SensorDef> = listOf(
    SensorDef(SensorId.RPM, "RPM", "rpm", 0.0, 7000.0, "3500", 0, true, obd(0x0C, 2, 0.25, 0.0), warnAbove = 5000.0),
    SensorDef(SensorId.BOOST, "BOOST", "bar", -1.0, 2.0, "0.5", 2, true, null, derived = true, warnAbove = 1.0),
    SensorDef(SensorId.GEAR, "GEAR", "", 0.0, 8.0, "", 0, true, DidSource(EGS, 0xDA2E, 1, 1.0, 0.0, pos = 1)),
    SensorDef(SensorId.WATER_TEMP, "WATER TEMP", "°C", 50.0, 130.0, "90", 0, false, obd(0x05, 1, 1.0, -40.0), warnAbove = 112.0),
    SensorDef(SensorId.OIL_TEMP, "OIL TEMP", "°C", 50.0, 150.0, "100", 0, false, DidSource(DME, 0x4402, 2, 0.75, -48.0), warnAbove = 130.0),
    SensorDef(SensorId.INTAKE_TEMP, "INTAKE AIR TEMP", "°C", -20.0, 80.0, "20", 0, false, obd(0x0F, 1, 1.0, -40.0), warnAbove = 60.0),
    SensorDef(SensorId.MAP, "MAP / BOOST", "kPa", 0.0, 255.0, "MAP ABS", 0, true, DidSource(DME, 0x4205, 2, 0.0078125, 0.0)),
    SensorDef(SensorId.BARO, "BARO", "kPa", 80.0, 110.0, "", 1, false, DidSource(DME, 0x4201, 2, 0.00390625, 0.0)),
    SensorDef(SensorId.THROTTLE, "THROTTLE", "%", 0.0, 100.0, "50", 0, true, obd(0x11, 1, 100.0 / 255.0, 0.0)),
    SensorDef(SensorId.BATTERY, "BATTERY", "V", 11.0, 16.0, "13", 1, false, obd(0x42, 2, 0.001, 0.0)),
    SensorDef(SensorId.SPEED, "SPEED", "km/h", 0.0, 240.0, "120", 0, true, obd(0x0D, 1, 1.0, 0.0)),
    SensorDef(SensorId.FUEL_TRIM, "FUEL TRIM ST", "%", -25.0, 25.0, "0", 1, true, obd(0x06, 1, 100.0 / 128.0, -100.0)),
    SensorDef(SensorId.AFR, "AIR-FUEL RATIO", ":1", 10.0, 18.0, "14", 1, true, obd(0x34, 2, 14.7 * 2.0 / 65536.0, 0.0)),
    SensorDef(SensorId.OIL_PRESSURE, "OIL PRESSURE", "bar", 0.0, 7.0, "4", 1, true, DidSource(DME, 0x586F, 2, 0.001, -1.0)),
    SensorDef(SensorId.TRANS_TEMP, "TRANSMISSION TEMP", "°C", 40.0, 140.0, "90", 0, false, DidSource(EGS, 0xDA12, 1, 1.0, -40.0), warnAbove = 120.0),
    SensorDef(SensorId.RUN_TIME, "ENGINE RUN TIME", "h:min:s", 0.0, 0.0, "", 0, false, obd(0x1F, 2, 1.0, 0.0)),
)

val SENSOR_MAP: Map<SensorId, SensorDef> = SENSORS.associateBy { it.id }

fun SensorDef.formatValue(v: Double?): String {
    if (v == null) return "--"
    return when (id) {
        SensorId.RUN_TIME -> {
            val s = v.toLong()
            String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
        }
        SensorId.GEAR -> if (v.toInt() in 1..8) v.toInt().toString() else "–"
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
