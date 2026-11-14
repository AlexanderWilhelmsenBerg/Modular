package io.github.alexanderwilhelmsenberg.modular.moduleapi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ModuleDescriptorCodecTest {
    private val descriptor = """{
        "schemaVersion":1,
        "moduleId":"dev.example.sort",
        "moduleVersion":"1.0.0",
        "displayName":"Example sorter",
        "description":"Orders apps",
        "coreProtocol":{"min":4,"max":4},
        "providedCapabilities":["modular.apps.sort/v1"],
        "requestedCoreCapabilities":[],
        "instanceConfiguration":{"schemaVersion":1,"fields":[
          {"key":"descending","title":"Descending","type":"boolean","default":false},
          {"key":"limit","title":"Limit","type":"integer","default":10,"min":1,"max":100},
          {"key":"label","title":"Label","type":"string","default":"All","maxLength":32}
        ]}
    }""".trimIndent()

    @Test fun protocolBoundsPreserveOlderVersions() {
        for (version in 1..4) assertTrue(ModuleContract.isProtocolSupported(version))
        assertFalse(ModuleContract.isProtocolSupported(5))
        assertFalse(ModuleContract.supportsInstanceConfiguration(3))
        assertTrue(ModuleContract.supportsInstanceConfiguration(4))
        assertEquals("io.github.alexanderwilhelmsenberg.modular.MODULE_DESCRIPTOR", ModuleContract.MODULE_DESCRIPTOR_METADATA_KEY)
    }

    @Test fun parsesAndNormalizesDefaultsInStableOrder() {
        val parsed = ModuleDescriptorCodec.parse(descriptor)
        assertEquals("dev.example.sort", parsed.moduleId)
        assertEquals(4, parsed.coreProtocolMin)
        assertEquals("""{"descending":false,"label":"All","limit":10}""",
            ModuleDescriptorCodec.normalizeConfiguration(parsed, "{}"))
    }

    @Test fun emptySchemaAcceptsEmptyConfiguration() {
        val empty = """{"schemaVersion":1,"moduleId":"dev.example.empty","moduleVersion":"1","displayName":"Empty","description":"","coreProtocol":{"min":4,"max":4},"providedCapabilities":["modular.apps.sort/v1"],"requestedCoreCapabilities":[],"instanceConfiguration":{"schemaVersion":1,"fields":[]}}"""
        assertEquals("{}", ModuleDescriptorCodec.normalizeConfiguration(ModuleDescriptorCodec.parse(empty), "{}"))
    }

    @Test fun workspaceActivityIsOptionalAndValidatedLikeManagementActivity() {
        assertEquals(null, ModuleDescriptorCodec.parse(descriptor).workspaceActivity)
        val withActivity = descriptor.replace(
            "\"instanceConfiguration\":", "\"workspaceActivity\":\".WorkspaceActivity\",\"instanceConfiguration\":")
        assertEquals(".WorkspaceActivity", ModuleDescriptorCodec.parse(withActivity).workspaceActivity)
        rejects { ModuleDescriptorCodec.parse(withActivity.replace(".WorkspaceActivity", "other/package.Activity")) }
    }

    @Test fun workspaceSurfaceMinimumDefaultsTo30AndRoundTrips() {
        assertEquals(30, ModuleDescriptorCodec.parse(descriptor).workspaceSurfaceMinSdk)
        val api33 = descriptor.replace(
            "\"instanceConfiguration\":", "\"workspaceSurfaceMinSdk\":33,\"instanceConfiguration\":")
        val parsed = ModuleDescriptorCodec.parse(api33)
        assertEquals(33, parsed.workspaceSurfaceMinSdk)
        assertEquals(parsed, ModuleDescriptorCodec.parse(ModuleDescriptorCodec.encode(parsed)))
        assertEquals(30, ModuleDescriptorCodec.parse(
            ModuleDescriptorCodec.encode(ModuleDescriptorCodec.parse(descriptor))).workspaceSurfaceMinSdk)
    }

    @Test fun workspaceSurfaceMinimumRejectsUnsupportedValuesAndTypes() {
        for (candidate in listOf("29", "10001", "-1", "33.5", "\"33\"", "null")) {
            val raw = descriptor.replace(
                "\"instanceConfiguration\":", "\"workspaceSurfaceMinSdk\":$candidate,\"instanceConfiguration\":")
            rejects { ModuleDescriptorCodec.parse(raw) }
        }
        val valid = ModuleDescriptorCodec.parse(descriptor)
        rejects { ModuleDescriptorCodec.encode(valid.copy(workspaceSurfaceMinSdk = 29)) }
    }

    @Test fun rejectsInvalidDescriptorAndUnknownFields() {
        rejects { ModuleDescriptorCodec.parse(descriptor.replace("\"schemaVersion\":1", "\"schemaVersion\":2")) }
        rejects { ModuleDescriptorCodec.parse(descriptor.replace("\"max\":4", "\"max\":3")) }
        rejects { ModuleDescriptorCodec.parse(descriptor.replace("\"fields\":[", "\"unknown\":true,\"fields\":[")) }
        rejects { ModuleDescriptorCodec.parse(descriptor + "x") }
        rejects { ModuleDescriptorCodec.parse(" ".repeat(ModuleDescriptorCodec.MAX_DESCRIPTOR_BYTES + 1)) }
    }

    @Test fun rejectsUnknownKeysWrongTypesBoundsAndSizeWithoutEchoingInput() {
        val parsed = ModuleDescriptorCodec.parse(descriptor)
        val marker = "PRIVATE_USER_VALUE"
        listOf(
            """{"unknown":"$marker"}""",
            """{"descending":"true"}""",
            """{"descending":{"value":true}}""",
            """{"limit":101}""",
            """{"limit":1.5}""",
            """{"label":null}""",
            """{"label":"${"x".repeat(33)}"}""",
            " ".repeat(ModuleDescriptorCodec.MAX_CONFIGURATION_BYTES + 1),
        ).forEach { candidate ->
            val error = rejects { ModuleDescriptorCodec.normalizeConfiguration(parsed, candidate) }
            assertFalse(error.message.orEmpty().contains(marker))
        }
    }

    @Test fun independentInstancesKeepIndependentNormalizedValues() {
        val parsed = ModuleDescriptorCodec.parse(descriptor)
        val first = ModuleDescriptorCodec.normalizeConfiguration(parsed, """{"descending":true,"limit":2}""")
        val second = ModuleDescriptorCodec.normalizeConfiguration(parsed, """{"descending":false,"limit":20}""")
        assertEquals("""{"descending":true,"label":"All","limit":2}""", first)
        assertEquals("""{"descending":false,"label":"All","limit":20}""", second)
    }

    private fun rejects(block: () -> Unit): IllegalArgumentException =
        try {
            block()
            fail("Expected validation to fail")
            throw AssertionError("unreachable")
        } catch (error: IllegalArgumentException) {
            error
        }
}
