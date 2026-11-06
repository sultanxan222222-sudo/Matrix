package org.matrixvpn.caravan

enum class CaravanFailure {
    CONNECT_TIMEOUT,
    AUTHENTICATION_REJECTED,
    TRANSPORT_BLOCKED,
    TUNNEL_DROPPED,
    EGRESS_CHECK_FAILED,
    NETWORK_UNAVAILABLE,
    USER_CANCELLED
}

enum class CaravanAction {
    TRY_NEXT_ROUTE,
    WAIT_FOR_NETWORK,
    STOP
}

/**
 * The caravan policy never retries a failed relay indefinitely. It excludes
 * attempted relays, waits for the underlying network when it is gone, and
 * leaves cancellation under the user's control.
 */
object CaravanRecoveryPolicy {
    fun nextAction(failure: CaravanFailure, hasAlternative: Boolean): CaravanAction =
        when {
            failure == CaravanFailure.USER_CANCELLED -> CaravanAction.STOP
            failure == CaravanFailure.NETWORK_UNAVAILABLE -> CaravanAction.WAIT_FOR_NETWORK
            hasAlternative -> CaravanAction.TRY_NEXT_ROUTE
            else -> CaravanAction.STOP
        }

    fun shouldScoutAlternatives(failure: CaravanFailure): Boolean =
        failure != CaravanFailure.USER_CANCELLED &&
            failure != CaravanFailure.NETWORK_UNAVAILABLE
}
