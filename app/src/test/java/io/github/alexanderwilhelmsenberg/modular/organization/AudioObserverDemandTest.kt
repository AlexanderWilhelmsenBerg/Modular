package io.github.alexanderwilhelmsenberg.modular.organization

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioObserverDemandTest {
    @Test fun oneObserverIsNeededForEitherForegroundConsumer() {
        val rules = mapOf(
            "headset" to FolderCondition.Headset("headset"),
            "disabled" to FolderCondition.Headset("disabled", enabled = false),
            "wifi" to FolderCondition.Wifi("wifi"),
        )
        val smart = FolderDefinition("smart", "Smart", visibilityMode = FolderVisibilityMode.SMART,
            conditionIds = listOf("headset"))
        assertFalse(audioObserverNeeded(emptyList(), rules, listeningEnabled = false))
        assertTrue(audioObserverNeeded(emptyList(), rules, listeningEnabled = true))
        assertTrue(audioObserverNeeded(listOf(smart), rules, listeningEnabled = false))
        assertTrue(audioObserverNeeded(listOf(smart), rules, listeningEnabled = true))
        assertFalse(audioObserverNeeded(listOf(smart.copy(visibilityMode = FolderVisibilityMode.ALWAYS)),
            rules, listeningEnabled = false))
        assertFalse(audioObserverNeeded(listOf(smart.copy(conditionIds = listOf("disabled", "wifi"))),
            rules, listeningEnabled = false))
    }
}
