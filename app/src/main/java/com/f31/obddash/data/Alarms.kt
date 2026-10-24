package com.f31.obddash.data

data class Alarm(val id: SensorId, val text: String)

/** Critical warnings: flashing tile + banner + sound. Engine-running aware. */
fun computeAlarms(v: Map<SensorId, Double>): List<Alarm> {
    val running = (v[SensorId.RPM] ?: 0.0) > 400.0
    val out = ArrayList<Alarm>()
    val op = v[SensorId.OIL_PRESSURE]
    if (running && op != null && op < 0.7) out += Alarm(SensorId.OIL_PRESSURE, "OIL PRESSURE LOW")
    val wt = v[SensorId.WATER_TEMP]
    if (wt != null && wt > 112.0) out += Alarm(SensorId.WATER_TEMP, "COOLANT HOT")
    val ot = v[SensorId.OIL_TEMP]
    if (ot != null && ot > 135.0) out += Alarm(SensorId.OIL_TEMP, "OIL TEMP HIGH")
    val bat = v[SensorId.BATTERY]
    if (running && bat != null && bat < 12.5) out += Alarm(SensorId.BATTERY, "CHARGING FAULT")
    return out
}

const val REDLINE_COLD_RPM = 4000.0
const val REDLINE_HOT_RPM = 5000.0
private const val OIL_COLD_C = 40.0
private const val OIL_HOT_C = 85.0

/** BMW M-style variable redline: lower while the engine oil is cold. */
fun redlineRpm(oilTemp: Double?): Double {
    if (oilTemp == null) return REDLINE_HOT_RPM
    val f = ((oilTemp - OIL_COLD_C) / (OIL_HOT_C - OIL_COLD_C)).coerceIn(0.0, 1.0)
    return REDLINE_COLD_RPM + f * (REDLINE_HOT_RPM - REDLINE_COLD_RPM)
}
