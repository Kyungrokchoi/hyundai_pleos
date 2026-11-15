package io.moata.pleos.core.vehicle


enum class DataSource { PLEOS, MANUAL, DEMO }

enum class Quality { VERIFIED, ESTIMATED, UNAVAILABLE, INVALID }


enum class SignalType(val unit: String) {
    ODOMETER("km"),
    FUEL_ECONOMY("L/100km"),
    EV_EFFICIENCY("km/kWh"),
    EV_SOC("%"),
}

const val PLEOS_SDK_VERSION = "2.3.2"





data class VehicleSample(
    val type: SignalType,
    val value: Double?,
    val unit: String,
    val capturedAtEpochMs: Long,
    val source: DataSource,
    val quality: Quality,
    val sdkVersion: String?,
    val error: String? = null,
) {
    val usableValue: Double?
        get() = value?.takeIf { quality == Quality.VERIFIED || quality == Quality.ESTIMATED }

    val usable: Boolean get() = usableValue != null
}


object SampleRules {




    fun implausibleOdometer(km: Double, highestSeen: Double?): Boolean = km <= 0.0 || (highestSeen != null && km < highestSeen - 0.5)


    fun floatToDouble(f: Float): Double = f.toString().toDouble()

    fun classify(type: SignalType, raw: Double?): Pair<Quality, String?> = when {
        raw == null -> Quality.UNAVAILABLE to "NULL_VALUE: no value was returned"
        raw.isNaN() || raw.isInfinite() -> Quality.INVALID to "NOT_FINITE: $raw"
        type == SignalType.ODOMETER && raw < 0 -> Quality.INVALID to "NEGATIVE_ODOMETER: $raw"
        (type == SignalType.FUEL_ECONOMY || type == SignalType.EV_EFFICIENCY) && raw <= 0 ->
            Quality.INVALID to "NON_POSITIVE_EFFICIENCY: $raw"
        type == SignalType.EV_SOC && (raw < 0 || raw > 100) -> Quality.INVALID to "SOC_OUT_OF_RANGE: $raw"
        else -> Quality.VERIFIED to null
    }
}
