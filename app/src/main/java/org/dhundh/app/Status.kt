package org.dhundh.app

import android.os.Handler
import android.os.Looper

/** What the screen shows. Updated by the service, observed by the activity. */
data class State(
    val running: Boolean = false,
    val codedPhy: Boolean = false,
    val extended: Boolean = false,
    val speedKmh: Float = 0f,
    val gpsAccuracyM: Float = Float.NaN,
    val sent: Int = 0,
    val received: Int = 0,
    val lastEvent: String = "",
)

object Status {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<(State) -> Unit>()

    @Volatile
    var state = State()
        private set

    fun update(change: (State) -> State) {
        main.post {
            state = change(state)
            listeners.toList().forEach { it(state) }
        }
    }

    fun observe(listener: (State) -> Unit) {
        listeners += listener
        listener(state)
    }

    fun remove(listener: (State) -> Unit) {
        listeners -= listener
    }
}
