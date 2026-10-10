package com.xmen12c.bgtube

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tiny event bus between the playback service and the UI.
 */
object PlayerHub {
    data class State(
        val videoId: String? = null,
        val title: String? = null,
        val isPlaying: Boolean = false,
        val notice: String? = null,
    )

    @Volatile
    private var current = State()

    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()

    val state: State get() = current

    fun observe(listener: (State) -> Unit) {
        listeners.add(listener)
        listener(current)
    }

    fun unobserve(listener: (State) -> Unit) {
        listeners.remove(listener)
    }

    fun update(state: State) {
        current = state
        listeners.forEach { it(state) }
    }
}
