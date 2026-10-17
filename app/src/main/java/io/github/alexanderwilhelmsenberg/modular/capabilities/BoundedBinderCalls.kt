package io.github.alexanderwilhelmsenberg.modular.capabilities

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore

/**
 * Binder transactions cannot be reliably interrupted. Bound waiting and keep
 * each timed-out slot occupied until the native transaction actually returns.
 * Never automatically retry a timed-out action: its outcome may be unknown.
 */
object BoundedBinderCalls {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val slots = Semaphore(8)
    private val keys = mutableSetOf<String>()

    suspend fun <T> call(key: String, timeoutMs: Long = 5000, operation: () -> T): Result<T> {
        synchronized(keys) {
            if (!keys.add(key)) return Result.failure(IllegalStateException("Provider is still busy"))
        }
        if (!slots.tryAcquire()) {
            synchronized(keys) { keys.remove(key) }
            return Result.failure(IllegalStateException("Too many pending provider calls"))
        }
        val pending = scope.async {
            try { operation() } finally {
                slots.release()
                synchronized(keys) { keys.remove(key) }
            }
        }
        return try {
            Result.success(withTimeout(timeoutMs) { pending.await() })
        } catch (_: TimeoutCancellationException) {
            Result.failure(IllegalStateException("Provider timed out; the operation outcome may be unknown"))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }

    fun forget(key: String, operation: () -> Unit) {
        scope.launch { call(key, operation = operation) }
    }
}
