package io.github.alexanderwilhelmsenberg.modular.composition

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Test

/** A new Activity wrapper may overlap the old one while Android recreates it. */
class DataStoreCompositionStateStoreLifecycleTest {
    @Test fun overlappingWrappersShareSavedStateAndClosingOneKeepsTheNextUsable(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = "composition-recreation-${UUID.randomUUID()}"
        val first = DataStoreCompositionStateStore(context, file)
        val second = DataStoreCompositionStateStore(context, file)
        var third: DataStoreCompositionStateStore? = null
        try {
            first.update { it.copy(contexts = listOf(LauncherContext("first", "First")), activeContextId = "first") }
            awaitContext(second, "first", "Second wrapper missed the first saved context")

            first.close()
            second.update { it.copy(contexts = listOf(LauncherContext("second", "Second")), activeContextId = "second") }
            val thirdStore = DataStoreCompositionStateStore(context, file)
            third = thirdStore
            val restored = awaitContext(thirdStore, "second", "Recreated wrapper missed the second saved context")
            assertEquals("Second", restored.contexts.single().name)

            second.close()
            thirdStore.update { it.copy(contexts = listOf(LauncherContext("third", "Third")), activeContextId = "third") }
            assertEquals("third", awaitContext(thirdStore, "third", "Remaining wrapper missed its update").activeContextId)
        } finally {
            first.close()
            second.close()
            third?.close()
        }
    }

    private suspend fun awaitContext(
        store: DataStoreCompositionStateStore,
        expected: String,
        message: String,
    ): CompositionState = withTimeoutOrNull(5_000) {
        store.state.first { it.activeContextId == expected }
    } ?: throw AssertionError("$message: state=${store.state.value}, loadError=${store.loadError.value}")
}
