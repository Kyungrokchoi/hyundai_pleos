package io.moata.pleos.core.vehicle

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Fixed demo trip: 24 km at 12 km/L. */
data class DemoFixture(
    val startKm: Double = 12_345.0,
    val totalKm: Double = 24.0,
    val stepKm: Double = 0.5,
    val kmPerLiter: Double = 12.0,
    val kmPerKwh: Double = 5.0,
)

/** Simulated vehicle, used only when the driver picks DEMO. Samples are DEMO / ESTIMATED, never VERIFIED. */
class DemoVehicleDataSource(
    private val fixture: DemoFixture = DemoFixture(),
    private val now: () -> Long = System::currentTimeMillis,
    private val tickMs: Long = 1_000,
) : VehicleDataSource {
    override val source = DataSource.DEMO
    @Volatile private var travelledKm = 0.0

    fun reset() { travelledKm = 0.0 }
    fun advance(km: Double) { travelledKm = (travelledKm + km).coerceIn(0.0, fixture.totalKm) }

    override suspend fun connect() = Result.success(Unit)
    override suspend fun disconnect() = Unit

    override suspend fun read(type: SignalType): VehicleSample = when (type) {
        SignalType.ODOMETER -> demo(type, fixture.startKm + travelledKm)
        // km/L, not the SDK's L/100km; the unit travels with the value
        SignalType.FUEL_ECONOMY -> demo(type, fixture.kmPerLiter, "km/L")
        SignalType.EV_EFFICIENCY -> demo(type, fixture.kmPerKwh)
        SignalType.EV_SOC -> VehicleSample(type, null, type.unit, now(), DataSource.DEMO, Quality.UNAVAILABLE, null, "NOT_SIMULATED")
    }

    override fun observeOdometer(): Flow<VehicleSample> = flow {
        while (travelledKm < fixture.totalKm) {
            emit(read(SignalType.ODOMETER))
            delay(tickMs)
            advance(fixture.stepKm)
        }
        emit(read(SignalType.ODOMETER))
    }

    private fun demo(type: SignalType, v: Double, unit: String = type.unit) =
        VehicleSample(type, v, unit, now(), DataSource.DEMO, Quality.ESTIMATED, null, null)
}
