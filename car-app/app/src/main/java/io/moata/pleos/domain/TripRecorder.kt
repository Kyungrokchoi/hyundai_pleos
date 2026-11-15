package io.moata.pleos.domain

import io.moata.pleos.core.vehicle.SignalType
import io.moata.pleos.core.vehicle.VehicleDataSource
import io.moata.pleos.core.vehicle.VehicleSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch


class TripRecorder(
    private val repo: TripRepository,
    private val scope: CoroutineScope,
    private val minIntervalMs: Long = 5_000,
    private val heartbeatMs: Long = 15_000,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var job: Job? = null
    private var beat: Job? = null
    private val _live = MutableStateFlow<VehicleSample?>(null)
    val live: StateFlow<VehicleSample?> = _live
    val running: Boolean get() = job?.isActive == true

    fun start(tripId: String, source: VehicleDataSource) {
        if (running) return
        val throttle = Throttle(minIntervalMs)
        job = scope.launch {
            source.observeOdometer().collect { sample ->
                _live.value = sample
                if (throttle.shouldRecord(now())) repo.recordReading(tripId, sample)
            }
        }

        beat = scope.launch {
            while (true) {
                delay(heartbeatMs)
                val sample = source.read(SignalType.ODOMETER)
                _live.value = sample
                if (throttle.shouldRecord(now())) repo.recordReading(tripId, sample)
            }
        }
    }

    fun stop() {
        job?.cancel(); beat?.cancel()
        job = null; beat = null
    }
}
