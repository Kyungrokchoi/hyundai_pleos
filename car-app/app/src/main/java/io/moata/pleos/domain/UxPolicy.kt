package io.moata.pleos.domain


enum class DrivingState { PARKED, DRIVING, UNKNOWN }

enum class Feature {
    QR_INVITE, TRIP_SETUP, COST_EDIT, TRIP_START_END, SETTLEMENT_REVIEW, GROUP_EDIT, SETTINGS,
    MOA_ANIMATION, REWARDS,
}

object UxPolicy {

    fun stateFrom(requiresDistractionOptimization: Boolean?): DrivingState = when (requiresDistractionOptimization) {
        null -> DrivingState.UNKNOWN
        true -> DrivingState.DRIVING
        false -> DrivingState.PARKED
    }

    fun isRestricted(state: DrivingState): Boolean = state != DrivingState.PARKED


    fun isAllowed(feature: Feature, state: DrivingState): Boolean = !isRestricted(state)
}
