package io.github.alexanderwilhelmsenberg.modular.composition

import io.github.alexanderwilhelmsenberg.modular.moduleapi.StandardCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CompositionStateCodecTest {
    @Test
    fun roundTripsInstancesContextsAndActiveSelection() {
        val state = CompositionState(
            instances = listOf(
                ModuleInstance(
                    id = "instance-work",
                    name = "Work sort",
                    providerId =
                        "modular.reference.sort.alphabetical",
                    configurationJson = """{"direction":"ascending"}""",
                ),
            ),
            contexts = listOf(
                LauncherContext(
                    id = "context-work",
                    name = "Work",
                    bindings = listOf(
                        ContextCapabilityBinding(
                            capability =
                                StandardCapabilities.AppsSort,
                            instanceId = "instance-work",
                        ),
                    ),
                ),
            ),
            activeContextId = "context-work",
        )

        assertEquals(
            state,
            CompositionStateCodec.decode(
                CompositionStateCodec.encode(state),
            ),
        )
    }

    @Test
    fun ignoresUnknownFieldsWithinKnownSchema() {
        val raw = """
            {
              "schemaVersion": 1,
              "instances": [],
              "contexts": [],
              "activeContextId": null,
              "futureField": "safe"
            }
        """.trimIndent()

        assertEquals(
            CompositionState(),
            CompositionStateCodec.decode(raw),
        )
    }

    @Test
    fun rejectsFutureSchemaVersion() {
        assertThrows(IllegalArgumentException::class.java) {
            CompositionStateCodec.decode(
                """{"schemaVersion":99}""",
            )
        }
    }
}
