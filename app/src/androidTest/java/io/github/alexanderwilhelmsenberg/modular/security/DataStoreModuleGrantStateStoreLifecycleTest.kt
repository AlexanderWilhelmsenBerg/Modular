package io.github.alexanderwilhelmsenberg.modular.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
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
            withTimeoutOrNull(5_000) { second.state.first { it.recordFor(firstIdentity) != null } }
                ?: throw AssertionError("Second wrapper missed first grant: first=${first.state.value} " +
                    "second=${second.state.value} firstError=${first.loadError.value} " +
                    "secondError=${second.loadError.value}")

            first.close()
            second.update { it.copy(records = it.records + ModuleGrantRecord(secondIdentity)) }
            val thirdStore = DataStoreModuleGrantStateStore(context, file)
            third = thirdStore
            val restored = withTimeoutOrNull(5_000) { thirdStore.state.first { it.records.size == 2 } }
                ?: throw AssertionError("Recreated wrapper missed grants: second=${second.state.value} " +
                    "third=${thirdStore.state.value} secondError=${second.loadError.value} " +
                    "thirdError=${thirdStore.loadError.value}")
            assertEquals(listOf(firstIdentity, secondIdentity), restored.records.map { it.identity })

            second.close()
            thirdStore.update { it.copy(records = it.records.filterNot { record -> record.identity == firstIdentity }) }
            assertEquals(secondIdentity, withTimeoutOrNull(5_000) {
                thirdStore.state.first { it.records.size == 1 }
            }?.records?.single()?.identity ?: throw AssertionError(
                "Recreated wrapper missed removal: third=${thirdStore.state.value} " +
                    "thirdError=${thirdStore.loadError.value}"))
        } finally {
            first.close()
            second.close()
            third?.close()
        }
    }
}
