package io.github.alexanderwilhelmsenberg.modular.addon.files

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.alexanderwilhelmsenberg.modular.moduleapi.WorkspacePageContract
import java.util.UUID
import java.util.regex.Pattern
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** A setup Activity cannot turn a guessed or replayed nonce into a system picker launch. */
@RunWith(AndroidJUnit4::class)
class FilesHandoffNonceTest {
    @Test fun forgedAndConsumedTokensDoNotOpenDocumentPicker() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val controller = (context as FilesApplication).pageController
        val device = UiDevice.getInstance(instrumentation)
        controller.openSettings()
        val page = JSONObject(controller.getPage("""{"version":1,"pageSize":100}"""))
        val actions = page.getJSONArray("actions")
        val add = (0 until actions.length()).map { actions.getJSONObject(it) }
            .single { it.optString("label") == "Add folder" }
        val request = JSONObject().put("version", 1).put("actionId", add.getString("id"))
            .put("operationToken", UUID.randomUUID().toString())
        val response = JSONObject(controller.performAction(request.toString()))
        val validNonce = response.getJSONObject("hostLaunch").getString("nonce")
        assertNotNull(controller.consumeHostHandoff(validNonce))
        assertNull("Handoff token must be single use", controller.consumeHostHandoff(validNonce))

        for (nonce in listOf("f".repeat(32), validNonce)) {
            ActivityScenario.launch<FilesSettingsActivity>(Intent(context, FilesSettingsActivity::class.java)
                .putExtra(WorkspacePageContract.EXTRA_HOST_HANDOFF_NONCE, nonce)).use {
                assertFalse("Forged/replayed nonce launched DocumentsUI",
                    device.wait(Until.hasObject(By.pkg(Pattern.compile(".*documentsui.*"))), 1_500))
            }
        }
    }
}
