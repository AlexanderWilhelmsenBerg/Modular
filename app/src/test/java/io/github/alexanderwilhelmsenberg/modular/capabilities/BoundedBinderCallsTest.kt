package io.github.alexanderwilhelmsenberg.modular.capabilities

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class BoundedBinderCallsTest {
    @Test fun timedOutNativeCallKeepsItsSlotUntilItActuallyReturns() = runBlocking {
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        try {
            val first = BoundedBinderCalls.call("blocked-test-provider", timeoutMs = 50) {
                try { release.await(5, TimeUnit.SECONDS) } finally { finished.countDown() }
            }
            assertTrue(first.isFailure)
            var retried = false
            assertTrue(BoundedBinderCalls.call("blocked-test-provider") { retried = true }.isFailure)
            assertFalse(retried)
        } finally { release.countDown() }
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        // Slot cleanup follows native return; await that condition with a deadline.
        withTimeout(5000) {
            while (BoundedBinderCalls.call("blocked-test-provider") { "ready" }.isFailure) delay(10)
        }
    }
}
