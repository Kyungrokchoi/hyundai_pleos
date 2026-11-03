package io.moata.pleos.core.vehicle

import kotlinx.coroutines.flow.Flow

/** `Vehicle.initialize()` returns nothing, so `connect()` only proves "no exception"; the first read proves the binding. */
interface VehicleDataSource {
    val source: DataSource
    suspend fun connect(): Result<Unit>
    suspend fun read(type: SignalType): VehicleSample
    /** Odometer updates (km). The flow ends and unregisters when collection stops. */
    fun observeOdometer(): Flow<VehicleSample>
    suspend fun disconnect()
}

suspend fun VehicleDataSource.snapshot(types: List<SignalType>): List<VehicleSample> = types.map { read(it) }
