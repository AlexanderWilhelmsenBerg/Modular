package io.github.alexanderwilhelmsenberg.modular.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

/** Activity recreation may overlap wrappers for the same grant file. */
class DataStoreModuleGrantStateStoreLifecycleTest {
    @Test fun overlappingWrappersKeepGrantsAcrossCloseAndRecreation(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = "grant-recreation-${UUID.randomUUID()}"
        val first = DataStoreModuleGrantStateStore(context, file)
        val second = DataStoreModuleGrantStateStore(context, file)
        var third: DataStoreModuleGrantStateStore? = null
        val firstIdentity = ModuleIdentity("fixture.first", listOf("a".repeat(64)))
        val secondIdentity = ModuleIdentity("fixture.second", listOf("b".repeat(64)))
        try {
            first.update { it.copy(records = listOf(ModuleGrantRecord(firstIdentity))) }
            withTimeout(5_000) { second.state.first { it.recordFor(firstIdentity) != null } }

            first.close()
            second.update { it.copy(records = it.records + ModuleGrantRecord(secondIdentity)) }
            val thirdStore = DataStoreModuleGrantStateStore(context, file)
            third = thirdStore
            val restored = withTimeout(5_000) { thirdStore.state.first { it.records.size == 2 } }
            assertEquals(listOf(firstIdentity, secondIdentity), restored.records.map { it.identity })

            second.close()
            thirdStore.update { it.copy(records = it.records.filterNot { record -> record.identity == firstIdentity }) }
            assertEquals(secondIdentity, withTimeout(5_000) {
                thirdStore.state.first { it.records.size == 1 }
            }.records.single().identity)
        } finally {
            first.close()
            second.close()
            third?.close()
        }
    }
}
