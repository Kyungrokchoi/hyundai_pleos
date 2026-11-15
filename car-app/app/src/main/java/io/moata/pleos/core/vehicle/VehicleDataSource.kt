package io.moata.pleos.core.vehicle

import kotlinx.coroutines.flow.Flow


interface VehicleDataSource {
    val source: DataSource
    suspend fun connect(): Result<Unit>
    suspend fun read(type: SignalType): VehicleSample

    fun observeOdometer(): Flow<VehicleSample>
    suspend fun disconnect()
}

suspend fun VehicleDataSource.snapshot(types: List<SignalType>): List<VehicleSample> = types.map { read(it) }
