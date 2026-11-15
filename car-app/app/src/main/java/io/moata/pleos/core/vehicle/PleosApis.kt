package io.moata.pleos.core.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.EvBattery
import ai.pleos.playground.vehicle.api.Fuel
import ai.pleos.playground.vehicle.api.Odometer
import android.content.Context


interface PleosApis {
    fun initialize()
    fun release()
    val odometer: Odometer
    val fuel: Fuel
    val evBattery: EvBattery
}


class RealPleosApis(context: Context) : PleosApis {
    private val vehicle = Vehicle(context.applicationContext)
    override fun initialize() = vehicle.initialize()
    override fun release() = vehicle.release()
    override val odometer: Odometer get() = vehicle.getOdometer()
    override val fuel: Fuel get() = vehicle.getFuel()
    override val evBattery: EvBattery get() = vehicle.getEvBattery()
}
