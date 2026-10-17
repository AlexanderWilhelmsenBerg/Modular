package io.github.alexanderwilhelmsenberg.modular.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.alexanderwilhelmsenberg.modular.composition.*
import io.github.alexanderwilhelmsenberg.modular.moduleapi.CoreCapabilities
import io.github.alexanderwilhelmsenberg.modular.security.*
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProcessPreferenceStoresInstrumentedTest {
    @Test fun overlappingOwnersShareFilesAndClosingObserversDoesNotCloseStorage() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val suffix = UUID.randomUUID().toString()
        val firstComposition = DataStoreCompositionStateStore(context, "shared-composition-$suffix")
        val secondComposition = DataStoreCompositionStateStore(context, "shared-composition-$suffix")
        val firstGrants = DataStoreModuleGrantStateStore(context, "shared-grants-$suffix")
        val secondGrants = DataStoreModuleGrantStateStore(context, "shared-grants-$suffix")
        try {
            val saved = LauncherContext("context", "Saved context", emptyList())
            firstComposition.update { it.copy(contexts = listOf(saved)) }
            withTimeout(10_000) { secondComposition.state.first { it.contexts == listOf(saved) } }
            val record = ModuleGrantRecord(ModuleIdentity("example.module", listOf("a".repeat(64))),
                setOf(CoreCapabilities.AppsCatalog))
            firstGrants.update { it.copy(records = listOf(record)) }
            withTimeout(10_000) { secondGrants.state.first { it.records == listOf(record) } }

            firstComposition.close()
            firstGrants.close()
            secondComposition.update { it.copy(contexts = listOf(saved.copy(name = "Changed"))) }
            secondGrants.update { it.copy(records = listOf(record.copy(grantedCapabilities = emptySet()))) }
            assertEquals("Changed", secondComposition.update { it }.contexts.single().name)
            assertEquals(emptySet<Any>(), secondGrants.update { it }.records.single().grantedCapabilities)
        } finally {
            firstComposition.close(); secondComposition.close()
            firstGrants.close(); secondGrants.close()
        }
    }
}
