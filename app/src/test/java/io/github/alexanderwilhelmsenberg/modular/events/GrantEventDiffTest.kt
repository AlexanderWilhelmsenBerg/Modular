package io.github.alexanderwilhelmsenberg.modular.events

import io.github.alexanderwilhelmsenberg.modular.moduleapi.CoreCapabilities
import io.github.alexanderwilhelmsenberg.modular.security.ModuleGrantRecord
import io.github.alexanderwilhelmsenberg.modular.security.ModuleGrantState
import io.github.alexanderwilhelmsenberg.modular.security.ModuleIdentity
import org.junit.Assert.assertEquals
import org.junit.Test

class GrantEventDiffTest {
    @Test
    fun grantDeltaUsesSignerBoundIdentityKeys() {
        val a = identity("a")
        val b = identity("b")

        val previous = ModuleGrantState(
            records = listOf(
                ModuleGrantRecord(a),
            ),
        )
        val current = ModuleGrantState(
            records = listOf(
                ModuleGrantRecord(
                    identity = a,
                    grantedCapabilities = setOf(
                        CoreCapabilities.AppsCatalog,
                    ),
                ),
                ModuleGrantRecord(b),
            ),
        )

        val event = requireNotNull(
            CoreEventDiffs.grants(
                previous = previous,
                current = current,
            ),
        )

        assertEquals(
            listOf(b.key),
            event.addedIdentityKeys,
        )
        assertEquals(
            emptyList<String>(),
            event.removedIdentityKeys,
        )
        assertEquals(
            listOf(a.key),
            event.changedIdentityKeys,
        )
    }

    private fun identity(
        fill: String,
    ) = ModuleIdentity(
        packageName =
            "example." + fill,
        signerSha256 =
            listOf(fill.repeat(64)),
    )
}
