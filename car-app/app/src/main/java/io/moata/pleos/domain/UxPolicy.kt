package io.moata.pleos.domain

/** PARKED only when the car says so. Anything else, including unknown, is restricted. */
enum class DrivingState { PARKED, DRIVING, UNKNOWN }

enum class Feature {
    QR_INVITE, TRIP_SETUP, COST_EDIT, TRIP_START_END, SETTLEMENT_REVIEW, GROUP_EDIT, SETTINGS,
    MOA_ANIMATION, REWARDS,
}

object UxPolicy {
    /** null (no answer yet or API missing) is UNKNOWN. */
    fun stateFrom(requiresDistractionOptimization: Boolean?): DrivingState = when (requiresDistractionOptimization) {
        null -> DrivingState.UNKNOWN
        true -> DrivingState.DRIVING
        false -> DrivingState.PARKED
    }

    fun isRestricted(state: DrivingState): Boolean = state != DrivingState.PARKED

    /** The read-only driving view is always available, so it is not a [Feature]. */
    fun isAllowed(feature: Feature, state: DrivingState): Boolean = !isRestricted(state)
}
