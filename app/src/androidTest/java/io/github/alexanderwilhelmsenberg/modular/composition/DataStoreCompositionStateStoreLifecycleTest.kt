package io.github.alexanderwilhelmsenberg.modular.composition

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
            withTimeout(5_000) { second.state.first { it.activeContextId == "first" } }

            first.close()
            second.update { it.copy(contexts = listOf(LauncherContext("second", "Second")), activeContextId = "second") }
            val thirdStore = DataStoreCompositionStateStore(context, file)
            third = thirdStore
            val restored = withTimeout(5_000) { thirdStore.state.first { it.activeContextId == "second" } }
            assertEquals("Second", restored.contexts.single().name)

            second.close()
            thirdStore.update { it.copy(contexts = listOf(LauncherContext("third", "Third")), activeContextId = "third") }
            assertEquals("third", withTimeout(5_000) { thirdStore.state.first { it.activeContextId == "third" } }.activeContextId)
        } finally {
            first.close()
            second.close()
            third?.close()
        }
    }
}
