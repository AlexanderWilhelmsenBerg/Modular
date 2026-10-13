package io.github.alexanderwilhelmsenberg.modular.security

import io.github.alexanderwilhelmsenberg.modular.moduleapi.CoreCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ModuleGrantStateCodecTest {
    @Test
    fun roundTripPreservesInitializedEmptyGrantRecord() {
        val identity = ModuleIdentity(
            packageName = "example.module",
            signerSha256 =
                listOf("a".repeat(64)),
        )
        val original = ModuleGrantState(
            records = listOf(
                ModuleGrantRecord(
                    identity = identity,
                    grantedCapabilities =
                        emptySet(),
                ),
            ),
        )

        val decoded = ModuleGrantStateCodec.decode(
            ModuleGrantStateCodec.encode(
                original,
            ),
        )

        assertEquals(original, decoded)
        assertEquals(
            emptySet<Any>(),
            decoded.records.single()
                .grantedCapabilities,
        )
    }

    @Test
    fun roundTripPreservesCapabilityGrant() {
        val original = ModuleGrantState(
            records = listOf(
                ModuleGrantRecord(
                    identity = ModuleIdentity(
                        packageName =
                            "example.module",
                        signerSha256 =
                            listOf("b".repeat(64)),
                    ),
                    grantedCapabilities = setOf(
                        CoreCapabilities.AppsCatalog,
                    ),
                ),
            ),
        )

        assertEquals(
            original,
            ModuleGrantStateCodec.decode(
                ModuleGrantStateCodec.encode(
                    original,
                ),
            ),
        )
    }

    @Test
    fun unknownFieldsAreToleratedWithinKnownSchema() {
        val decoded = ModuleGrantStateCodec.decode(
            """
            {
              "schemaVersion": 1,
              "records": [],
              "futureField": true
            }
            """.trimIndent(),
        )

        assertEquals(
            ModuleGrantState(),
            decoded,
        )
    }

    @Test
    fun futureSchemaFailsClosed() {
        assertThrows(
            IllegalArgumentException::class.java,
        ) {
            ModuleGrantStateCodec.decode(
                """
                {
                  "schemaVersion": 2,
                  "records": []
                }
                """.trimIndent(),
            )
        }
    }
}
