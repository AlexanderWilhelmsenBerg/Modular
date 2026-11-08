package io.github.alexanderwilhelmsenberg.modular.organization

import io.github.alexanderwilhelmsenberg.modular.apps.AppKey
import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MegaSorterControllerTest {
    private val first = app("first")
    private val second = app("second")
    private val initial = OrganizationSnapshot(categories = listOf(
        CategoryDefinition("blue", "Blue"), CategoryDefinition("green", "Green")))

    @Test fun overlappingRowsKeepStableSlotsAndPersistIndependentlyAfterExit() = runTest {
        val port = DeferredPort()
        val controller = MegaSorterController(port, backgroundScope, initial, listOf(first, second),
            "green", "blue")
        val originalKeys = controller.state.value.rows.map { it.app.key }
        assertTrue(controller.assign(first.key, SorterSide.RIGHT))
        assertFalse(controller.assign(first.key, SorterSide.RIGHT))
        assertTrue(controller.assign(second.key, SorterSide.LEFT))
        runCurrent()
        controller.exit()
        controller.updateCatalog(listOf(second, first, app("new")))
        assertEquals(originalKeys, controller.state.value.rows.map { it.app.key })
        port.complete("green", second.key)
        port.complete("blue", first.key)
        advanceTimeBy(500)
        runCurrent()
        assertEquals(2, port.saved.size)
        assertEquals(SorterRowPhase.ASSIGNED, controller.state.value.rows.first().phase)
        assertTrue(controller.state.value.undoAvailable)
        assertFalse(controller.state.value.active)
    }

    @Test fun deletionDisablesOnlyAffectedSideAndFailureRecoversRow() = runTest {
        val port = DeferredPort()
        val controller = MegaSorterController(port, backgroundScope, initial, listOf(first), "green", "blue")
        controller.updateSnapshot(initial.copy(categories = listOf(CategoryDefinition("blue", "Blue"))))
        assertFalse(controller.assign(first.key, SorterSide.LEFT))
        assertTrue(controller.assign(first.key, SorterSide.RIGHT))
        runCurrent()
        port.fail("blue", first.key)
        advanceTimeBy(200)
        runCurrent()
        assertEquals(SorterRowPhase.ERROR, controller.state.value.rows.single().phase)
        assertFalse(controller.state.value.undoAvailable)
        assertTrue(controller.assign(first.key, SorterSide.RIGHT))
    }

    @Test fun undoUsesExactPairAndDoesNotTouchOtherMembership() = runTest {
        val port = DeferredPort()
        val controller = MegaSorterController(port, backgroundScope, initial, listOf(first, second), "green", "blue")
        assertTrue(controller.assign(first.key, SorterSide.RIGHT))
        assertTrue(controller.assign(second.key, SorterSide.LEFT))
        runCurrent()
        port.complete("blue", first.key)
        port.complete("green", second.key)
        advanceTimeBy(500)
        runCurrent()
        assertTrue(controller.undo())
        runCurrent()
        // Undo targets the most recently completed pair (green/second) and leaves blue/first.
        assertEquals(listOf(MembershipKey("blue", first.key)), port.saved.keys.toList())
        assertTrue(controller.state.value.undoAvailable)
    }

    @Test fun transientUndoFailureKeepsRecordForRetry() = runTest {
        val port = DeferredPort()
        val controller = MegaSorterController(port, backgroundScope, initial,
            listOf(first), null, "blue")
        assertTrue(controller.assign(first.key, SorterSide.RIGHT))
        runCurrent()
        port.complete("blue", first.key)
        advanceTimeBy(500)
        runCurrent()
        port.failNextUndo = true
        assertTrue(controller.undo())
        runCurrent()
        assertTrue(controller.state.value.undoAvailable)
        assertTrue(controller.undo())
        runCurrent()
        assertFalse(controller.state.value.undoAvailable)
    }

    @Test fun observerStopsOnExitButAcceptedFailureStillReports() = runTest {
        val port = DeferredPort()
        val updates = MutableSharedFlow<OrganizationSnapshot>(extraBufferCapacity = 1)
        val failures = mutableListOf<String>()
        val controller = MegaSorterController(port, backgroundScope, initial,
            listOf(first), null, "blue", snapshotUpdates = updates,
            onBackgroundFailure = failures::add)
        runCurrent()
        assertEquals(1, updates.subscriptionCount.value)
        assertTrue(controller.assign(first.key, SorterSide.RIGHT))
        runCurrent()
        controller.exit()
        runCurrent()
        assertEquals(0, updates.subscriptionCount.value)
        port.fail("blue", first.key)
        advanceTimeBy(500)
        runCurrent()
        assertEquals(1, failures.size)
        assertTrue(failures.single().contains("Blue"))
    }

    @Test fun deletingBothDestinationsStopsObserver() = runTest {
        val port = DeferredPort()
        val updates = MutableSharedFlow<OrganizationSnapshot>(extraBufferCapacity = 1)
        val controller = MegaSorterController(port, backgroundScope, initial,
            listOf(first), "green", "blue", snapshotUpdates = updates)
        runCurrent()
        assertTrue(updates.tryEmit(initial.copy(categories = emptyList())))
        runCurrent()
        assertFalse(controller.state.value.active)
        assertEquals(0, updates.subscriptionCount.value)
    }

    private fun app(name: String) = LaunchableApp(AppKey("com.example.$name", "Main", 0),
        name, 0, true, null)

    private class DeferredPort : MegaSorterMembershipPort {
        val saved = linkedMapOf<MembershipKey, MembershipChange>()
        var failNextUndo = false
        private val waiting = mutableMapOf<MembershipKey, CompletableDeferred<Result<MembershipChange>>>()
        private var nextToken = 1L

        override suspend fun setMembership(categoryId: String, appKey: AppKey): Result<MembershipChange> {
            val pair = MembershipKey(categoryId, appKey)
            return waiting.getOrPut(pair) { CompletableDeferred() }.await().also { result ->
                result.getOrNull()?.let { saved[pair] = it }
                waiting.remove(pair)
            }
        }

        override suspend fun undoMembership(change: MembershipChange): Result<Unit> {
            if (failNextUndo) {
                failNextUndo = false
                return Result.failure(IllegalStateException("Storage failed"))
            }
            val pair = MembershipKey(change.categoryId, change.appKey)
            if (saved[pair]?.token != change.token) return Result.failure(IllegalStateException("Changed"))
            saved.remove(pair)
            return Result.success(Unit)
        }

        fun complete(categoryId: String, key: AppKey) {
            val pair = MembershipKey(categoryId, key)
            waiting.getValue(pair).complete(Result.success(MembershipChange(categoryId, key,
                MembershipOverride.AUTO, MembershipOverride.INCLUDE, nextToken++)))
        }

        fun fail(categoryId: String, key: AppKey) {
            waiting.getValue(MembershipKey(categoryId, key)).complete(
                Result.failure(IllegalStateException("Storage failed")))
        }
    }
}
