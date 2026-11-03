package io.moata.pleos.core.vehicle

/** Where a value came from. Always shown in the UI; never inferred. */
enum class DataSource { PLEOS, MANUAL, DEMO }

enum class Quality { VERIFIED, ESTIMATED, UNAVAILABLE, INVALID }

/** Units as documented in the Vehicle 2.3.2 SDK KDoc. */
enum class SignalType(val unit: String) {
    ODOMETER("km"),            // Odometer.getCurrentDistanceDriven
    FUEL_ECONOMY("L/100km"),   // Fuel.get*FuelEconomy, not km/L
    EV_EFFICIENCY("km/kWh"),   // EvBattery.get*EvEfficiency
    EV_SOC("%"),               // EvBattery.getEvBatteryStateOfCharge
}

const val PLEOS_SDK_VERSION = "2.3.2"

/**
 * `value` is the raw SDK number in `unit`; the server normalises it.
 * A failed read has value=null and an `error`, never a made-up number.
 */
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
    val usable: Boolean get() = value != null && (quality == Quality.VERIFIED || quality == Quality.ESTIMATED)
}

/** Out-of-range values are flagged INVALID, never clamped or replaced. */
object SampleRules {
    /**
     * Successful reads to distrust: 0 (the Pleos emulator returns it right after an app restart, before the real
     * value arrives) or lower than this car already reported.
     */
    fun implausibleOdometer(km: Double, highestSeen: Double?): Boolean = km <= 0.0 || (highestSeen != null && km < highestSeen - 0.5)

    /** Float.toString() gives the shortest decimal, avoiding float->double noise like 12345.6787109375. */
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
