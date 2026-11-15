package io.moata.pleos.domain

import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow





class CarUxStateSource(private val context: Context) {
    private val _state = MutableStateFlow(DrivingState.UNKNOWN)
    val state: StateFlow<DrivingState> = _state
    private var car: Car? = null

    fun start() {
        if (car != null) return
        try {
            car = Car.createCar(context, Handler(Looper.getMainLooper()), Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { c, ready ->
                if (!ready) { _state.value = DrivingState.UNKNOWN; return@createCar }
                try {
                    val mgr = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
                    _state.value = UxPolicy.stateFrom(mgr.currentCarUxRestrictions?.isRequiresDistractionOptimization)
                    mgr.registerListener { r -> _state.value = UxPolicy.stateFrom(r.isRequiresDistractionOptimization) }
                } catch (t: Throwable) {
                    _state.value = DrivingState.UNKNOWN
                }
            }
        } catch (t: Throwable) {
            _state.value = DrivingState.UNKNOWN
        }
    }

    fun stop() {
        runCatching { car?.disconnect() }
        car = null
        _state.value = DrivingState.UNKNOWN
    }
}
