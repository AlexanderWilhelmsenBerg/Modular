package io.github.alexanderwilhelmsenberg.modular.events

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Bounded, lifecycle-owned event bus for Core-internal state notifications.
 *
 * Publishers never block Android callbacks. When a slow subscriber stalls the
 * delivery actor, the bounded queue drops the oldest pending envelope. Every
 * envelope has a monotonic sequence, so consumers can detect the gap and resync
 * from authoritative state instead of trusting an incomplete delta stream.
 */
class CoreEventBus(
    bufferCapacity: Int = DEFAULT_BUFFER_CAPACITY,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : AutoCloseable {
    private val scope =
        CoroutineScope(SupervisorJob() + dispatcher)
    private val publishLock = Any()
    private val sequence = AtomicLong(0)
    private val closed = AtomicBoolean(false)

    private val _diagnostics =
        MutableStateFlow(CoreEventBusDiagnostics())
    val diagnostics: StateFlow<CoreEventBusDiagnostics> =
        _diagnostics.asStateFlow()

    private val queue = Channel<CoreEventEnvelope>(
        capacity = requirePositiveCapacity(bufferCapacity),
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
        onUndeliveredElement = {
            _diagnostics.value =
                _diagnostics.value.copy(
                    droppedBeforeDelivery =
                        _diagnostics.value
                            .droppedBeforeDelivery + 1,
                )
        },
    )

    private val _events =
        MutableSharedFlow<CoreEventEnvelope>(
            replay = 0,
            extraBufferCapacity = 0,
        )
    val events: SharedFlow<CoreEventEnvelope> =
        _events.asSharedFlow()

    init {
        scope.launch {
            for (envelope in queue) {
                _events.emit(envelope)
                _diagnostics.value =
                    _diagnostics.value.copy(
                        latestDeliveredSequence =
                            envelope.sequence,
                    )
            }
        }
    }

    fun publish(event: CoreEvent): Long =
        synchronized(publishLock) {
            check(!closed.get()) {
                "Core event bus is closed"
            }

            val nextSequence =
                sequence.incrementAndGet()
            val envelope = CoreEventEnvelope(
                sequence = nextSequence,
                event = event,
            )

            check(queue.trySend(envelope).isSuccess) {
                "Core event bus queue rejected an event"
            }

            _diagnostics.value =
                _diagnostics.value.copy(
                    latestPublishedSequence =
                        nextSequence,
                )

            nextSequence
        }

    fun publishAll(events: Iterable<CoreEvent>) {
        events.forEach(::publish)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }

        queue.close()
        scope.cancel()
    }

    companion object {
        const val DEFAULT_BUFFER_CAPACITY = 64

        private fun requirePositiveCapacity(
            value: Int,
        ): Int {
            require(value > 0) {
                "Event bus buffer capacity must be positive"
            }
            return value
        }
    }
}
