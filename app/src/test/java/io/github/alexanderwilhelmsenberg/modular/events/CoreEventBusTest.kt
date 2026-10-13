package io.github.alexanderwilhelmsenberg.modular.events

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreEventBusTest {
    @Test
    fun sequenceIsMonotonicAndBoundedQueueReportsDrops() =
        runTest {
            val dispatcher =
                StandardTestDispatcher(testScheduler)
            val bus = CoreEventBus(
                bufferCapacity = 2,
                dispatcher = dispatcher,
            )

            try {
                repeat(5) { index ->
                    bus.publish(
                        CoreEvent.ActiveContextChanged(
                            previousContextId = null,
                            currentContextId =
                                "context-" + index,
                        ),
                    )
                }

                assertEquals(
                    5L,
                    bus.diagnostics.value
                        .latestPublishedSequence,
                )
                assertEquals(
                    3L,
                    bus.diagnostics.value
                        .droppedBeforeDelivery,
                )

                runCurrent()

                assertEquals(
                    5L,
                    bus.diagnostics.value
                        .latestDeliveredSequence,
                )
            } finally {
                bus.close()
            }
        }

    @Test
    fun cursorDetectsSequenceGapAndCanReset() {
        val cursor = CoreEventCursor()

        assertEquals(
            CoreEventContinuity.First,
            cursor.observe(envelope(10)),
        )
        assertEquals(
            CoreEventContinuity.Continuous,
            cursor.observe(envelope(11)),
        )

        val gap = cursor.observe(envelope(14))

        assertTrue(gap is CoreEventContinuity.Gap)
        assertEquals(
            CoreEventContinuity.Gap(
                expectedSequence = 12,
                actualSequence = 14,
            ),
            gap,
        )

        cursor.reset()

        assertEquals(
            CoreEventContinuity.First,
            cursor.observe(envelope(30)),
        )
    }

    private fun envelope(
        sequence: Long,
    ) = CoreEventEnvelope(
        sequence = sequence,
        event = CoreEvent.ActiveContextChanged(
            previousContextId = null,
            currentContextId = "context",
        ),
    )
}
