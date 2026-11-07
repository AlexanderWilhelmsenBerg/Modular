package io.github.alexanderwilhelmsenberg.modular.addon.sort.mostused

import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppKeyParcel

/** Revision changes only for a durable change in the ordered pin set. */
internal data class PinState(val revision: Long = 0, val keys: List<AppKeyParcel> = emptyList()) {
    init {
        require(revision >= 0)
        require(keys.size <= MAX_PINS && keys.size == keys.toSet().size)
    }

    fun set(key: AppKeyParcel, pinned: Boolean): PinState? {
        val present = key in keys
        if (present == pinned) return this
        if (pinned && keys.size == MAX_PINS) return null
        if (revision == Long.MAX_VALUE) return null
        return copy(
            revision = revision + 1,
            keys = if (pinned) keys + key else keys.filterNot { it == key },
        )
    }

    fun reorder(ordered: List<AppKeyParcel>): PinState? {
        if (ordered.size != keys.size || ordered.size != ordered.toSet().size || ordered.toSet() != keys.toSet()) {
            return null
        }
        if (ordered == keys) return this
        if (revision == Long.MAX_VALUE) return null
        return copy(revision = revision + 1, keys = ordered.toList())
    }

    companion object {
        const val MAX_PINS = 50
    }
}
