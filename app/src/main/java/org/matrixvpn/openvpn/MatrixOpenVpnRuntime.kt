package org.matrixvpn.openvpn

import com.tim.basevpn.state.ConnectionState
import java.util.concurrent.CopyOnWriteArraySet

/**
 * In-process state bridge between the OpenVPN VpnService and the MATRIX screen.
 * Listener registrations are explicitly removable to avoid retaining Activities.
 */
object MatrixOpenVpnRuntime {
    fun interface Listener {
        fun onState(state: ConnectionState, detail: String?)
    }

    private val listeners = CopyOnWriteArraySet<Listener>()

    @Volatile
    var state: ConnectionState = ConnectionState.DISCONNECTED
        private set

    @Volatile
    var detail: String? = null
        private set

    fun observe(listener: Listener): AutoCloseable {
        listeners.add(listener)
        listener.onState(state, detail)
        return AutoCloseable { listeners.remove(listener) }
    }

    internal fun publish(next: ConnectionState, message: String? = null) {
        state = next
        detail = message
        listeners.forEach { listener ->
            runCatching { listener.onState(next, message) }
        }
    }
}
