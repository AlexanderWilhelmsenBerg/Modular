package io.github.alexanderwilhelmsenberg.modular.addon.files

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** A late passive read must never replace the screen produced by a user action. */
internal class FilesPageRequestGate {
    private val generation = AtomicLong()
    private val pending = AtomicInteger()
    fun beginRead(): Long? {
        val current = generation.get()
        return current.takeIf { pending.get() == 0 }
    }
    fun beginAction(): Long { pending.incrementAndGet(); return generation.incrementAndGet() }
    fun endAction() { pending.decrementAndGet() }
    fun mayApply(ticket: Long): Boolean = ticket == generation.get()
}
