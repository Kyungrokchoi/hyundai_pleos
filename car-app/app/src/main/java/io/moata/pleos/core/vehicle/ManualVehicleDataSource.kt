package io.moata.pleos.core.vehicle

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map

/** Dashboard values typed by the driver when the SDK is unavailable. Labelled MANUAL. */
class ManualVehicleDataSource(private val now: () -> Long = System::currentTimeMillis) : VehicleDataSource {
    override val source = DataSource.MANUAL
    private val odometerKm = MutableStateFlow<Double?>(null)
    private val economy = MutableStateFlow<Pair<Double, String>?>(null)

    fun setOdometer(km: Double?) { odometerKm.value = km }
    fun setEconomy(value: Double, unit: String) { economy.value = value to unit }

    override suspend fun connect() = Result.success(Unit)
    override suspend fun disconnect() = Unit

    override suspend fun read(type: SignalType): VehicleSample = when (type) {
        SignalType.ODOMETER -> from(type, odometerKm.value, type.unit)
        SignalType.FUEL_ECONOMY, SignalType.EV_EFFICIENCY -> {
            val e = economy.value
            from(type, e?.first, e?.second ?: type.unit)
        }
        SignalType.EV_SOC -> from(type, null, type.unit)
    }

    override fun observeOdometer(): Flow<VehicleSample> = odometerKm.filterNotNull().map { from(SignalType.ODOMETER, it, "km") }

    private fun from(type: SignalType, v: Double?, unit: String): VehicleSample {
        if (v == null) return VehicleSample(type, null, unit, now(), DataSource.MANUAL, Quality.UNAVAILABLE, null, "NOT_ENTERED: 아직 입력되지 않았어요")
        val (q, err) = SampleRules.classify(type, v)
        val quality = if (q == Quality.VERIFIED) Quality.ESTIMATED else q // typed by a person, so never VERIFIED
        return VehicleSample(type, v, unit, now(), DataSource.MANUAL, quality, null, err)
    }
}
