package io.moata.pleos.core.vehicle

import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow





class PleosVehicleDataSource(
    private val apis: PleosApis,
    private val now: () -> Long = System::currentTimeMillis,
    private val timeoutMs: Long = 3_000,
) : VehicleDataSource {
    override val source = DataSource.PLEOS
    private var connected = false

    override suspend fun connect(): Result<Unit> = runCatching {
        apis.initialize()
        connected = true
    }

    override suspend fun disconnect() {
        if (!connected) return
        connected = false
        runCatching { apis.release() }
    }

    private enum class Cap { READABLE, NONE, UNKNOWN }

    private suspend fun capability(type: SignalType): Pair<Cap, String?> {
        val outcome = awaitCallback<CapabilityConfig>(timeoutMs) { ok, fail ->
            when (type) {
                SignalType.ODOMETER -> apis.odometer.checkCurrentDistanceDrivenCapabilities(ok, fail)
                SignalType.FUEL_ECONOMY -> apis.fuel.checkCurrentFuelEconomyCapabilities(ok, fail)
                SignalType.EV_EFFICIENCY -> apis.evBattery.checkCurrentEvEfficiencyCapabilities(ok, fail)
                SignalType.EV_SOC -> apis.evBattery.checkEvBatteryStateOfChargeCapabilities(ok, fail)
            }
        }
        return when (outcome) {
            is CallbackOutcome.Complete -> when (outcome.value.accessType) {
                AccessType.READ, AccessType.READ_WRITE -> Cap.READABLE to null
                else -> Cap.NONE to "CAPABILITY_${outcome.value.accessType}: the vehicle does not allow reading this signal"
            }

            else -> Cap.UNKNOWN to outcome.describe()
        }
    }

    override suspend fun read(type: SignalType): VehicleSample {
        if (!connected) return sample(type, null, Quality.UNAVAILABLE, "NOT_CONNECTED: connect() did not succeed")
        val (cap, capNote) = capability(type)
        if (cap == Cap.NONE) return sample(type, null, Quality.UNAVAILABLE, capNote)

        val outcome: CallbackOutcome<Double?> = when (type) {
            SignalType.ODOMETER -> awaitCallback<Float?>(timeoutMs) { ok, fail ->
                apis.odometer.getCurrentDistanceDriven(ok, fail)
            }.mapFloat()
            SignalType.FUEL_ECONOMY -> awaitCallback<Float?>(timeoutMs) { ok, fail ->
                apis.fuel.getCurrentFuelEconomy(ok, fail)
            }.mapFloat()
            SignalType.EV_EFFICIENCY -> awaitCallback<Float?>(timeoutMs) { ok, fail ->
                apis.evBattery.getCurrentEvEfficiency(ok, fail)
            }.mapFloat()
            SignalType.EV_SOC -> awaitCallback<Int?>(timeoutMs) { ok, fail ->
                apis.evBattery.getEvBatteryStateOfCharge(ok, fail)
            }.let { o -> if (o is CallbackOutcome.Complete) CallbackOutcome.Complete(o.value?.toDouble()) else o as CallbackOutcome<Double?> }
        }
        return when (outcome) {
            is CallbackOutcome.Complete -> {
                val (q, err) = SampleRules.classify(type, outcome.value)
                sample(type, outcome.value, q, err)
            }
            else -> sample(type, null, Quality.UNAVAILABLE, outcome.describe() + (capNote?.let { " [capability: $it]" } ?: ""))
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun CallbackOutcome<Float?>.mapFloat(): CallbackOutcome<Double?> =
        if (this is CallbackOutcome.Complete) CallbackOutcome.Complete(value?.let(SampleRules::floatToDouble))
        else this as CallbackOutcome<Double?>

    override fun observeOdometer(): Flow<VehicleSample> = callbackFlow {
        val listener = object : DistanceDrivenListener {
            override fun onDistanceDrivenUpdated(value: Float?) {
                val raw = value?.let(SampleRules::floatToDouble)
                val (q, err) = SampleRules.classify(SignalType.ODOMETER, raw)
                trySend(sample(SignalType.ODOMETER, raw, q, err))
            }
            override fun onFailed(e: Exception) {
                trySend(sample(SignalType.ODOMETER, null, Quality.UNAVAILABLE, "FAILED(listener): ${e::class.java.name}: ${e.message}"))
            }
        }
        if (!connected) {
            trySend(sample(SignalType.ODOMETER, null, Quality.UNAVAILABLE, "NOT_CONNECTED: connect() did not succeed"))
        } else {
            try {
                apis.odometer.registerDistanceDriven(listener)
            } catch (t: Throwable) {
                trySend(sample(SignalType.ODOMETER, null, Quality.UNAVAILABLE, "THREW(register): ${t::class.java.name}: ${t.message}"))
            }
        }
        awaitClose { if (connected) runCatching { apis.odometer.unregisterDistanceDriven(listener) } }
    }

    private fun sample(type: SignalType, v: Double?, q: Quality, err: String?) =
        VehicleSample(type, v, type.unit, now(), DataSource.PLEOS, q, PLEOS_SDK_VERSION, err)
}
