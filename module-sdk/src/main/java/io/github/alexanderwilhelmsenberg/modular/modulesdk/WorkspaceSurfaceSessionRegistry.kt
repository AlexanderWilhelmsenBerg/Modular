package io.github.alexanderwilhelmsenberg.modular.modulesdk

/** Atomic limit and revocation gate. Teardown always runs after its lock is released. */
internal class WorkspaceSurfaceSessionRegistry<T>(private val limit: Int) {
    private val lock = Any()
    private val sessions = LinkedHashSet<T>()
    private var closed = false

    fun add(session: T) = synchronized(lock) {
        check(!closed) { "Surface endpoint revoked" }
        check(sessions.size < limit) { "Surface session limit reached" }
        check(sessions.add(session)) { "Duplicate surface session" }
    }

    fun remove(session: T) = synchronized(lock) { sessions.remove(session) }

    fun closeAndDrain(): List<T> = synchronized(lock) {
        closed = true
        sessions.toList().also { sessions.clear() }
    }
}
