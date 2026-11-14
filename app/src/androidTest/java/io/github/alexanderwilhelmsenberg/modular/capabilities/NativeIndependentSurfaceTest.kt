package io.github.alexanderwilhelmsenberg.modular.capabilities

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.os.Bundle
import android.view.KeyEvent
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import io.github.alexanderwilhelmsenberg.modular.MainActivity
import io.github.alexanderwilhelmsenberg.modular.modules.ModuleManagementStore
import io.github.alexanderwilhelmsenberg.modular.organization.HomePageId
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationBatch
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationEdit
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationStore
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** CI installs the separately signed v1 APK and stages v2 at [V2_APK_PATH]. */
class NativeIndependentSurfaceTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val device by lazy { UiDevice.getInstance(instrumentation) }

    @Test fun externalNativeSurfaceSurvivesRebindAndOnlyItsApkUpdates() {
        val context = instrumentation.targetContext
        val packageManager = context.packageManager
        val coreBefore = installedApk(context, context.packageName)
        assertEquals(1L, packageManager.getPackageInfo(MODULE_PACKAGE, 0).longVersionCode)
        assertNotEquals(context.applicationInfo.uid,
            packageManager.getApplicationInfo(MODULE_PACKAGE, 0).uid)
        assertNotEquals("Fixture must have an independent signing identity",
            PackageManager.SIGNATURE_MATCH,
            packageManager.checkSignatures(context.packageName, MODULE_PACKAGE))

        val component = ComponentName(MODULE_PACKAGE, "$MODULE_PACKAGE.SurfaceModuleService")
        val management = ModuleManagementStore(context)
        val wasEnabled = management.isEnabled(component)
        // Use the same durable Core management choice as an ordinary community module.
        management.setEnabled(component, true)
        val store = OrganizationStore(context)
        var addedComponent: String? = null
        try {
            rule.activityRule.scenario.recreate()
            openHomeSettings()
            val add = "Add Independent surface test module to Home screens"
            rule.waitUntil(30_000) {
                runCatching { rule.onNodeWithText(add).performScrollTo().assertIsDisplayed() }.isSuccess
            }
            rule.onNodeWithText(add).performClick()
            rule.onNodeWithText("Right of Home").performClick()
            rule.waitUntil(15_000) {
                runBlocking { store.currentSnapshot().modulePages.any { it.component == component.flattenToString() } }
            }
            val snapshot = runBlocking { store.currentSnapshot() }
            val placed = snapshot.modulePages.single { it.component == component.flattenToString() }
            addedComponent = placed.component
            val homeIndex = snapshot.pageOrder.indexOf(HomePageId.Home)
            assertEquals(HomePageId.Module(placed.component), snapshot.pageOrder[homeIndex + 1])
            closeHomeSettings()

            swipePage(left = true)
            nativeVisible("Independent surface v1")
            captureProof("v1.png")
            assertTrue("v2 control must not be shipped in v1",
                device.findObject(By.desc("New v2 native control")) == null)
            requireNotNull(device.findObject(By.desc("Tap native control"))).click()
            nativeVisible("Surface taps: 1")
            val input = requireNotNull(device.findObject(By.desc("Module text input")))
            input.click()
            rule.waitUntil(5_000) {
                rule.runOnIdle {
                    ViewCompat.getRootWindowInsets(rule.activity.window.decorView)
                        ?.isVisible(WindowInsetsCompat.Type.ime()) == true
                }
            }
            assertTrue("OS key injection failed", device.pressKeyCode(KeyEvent.KEYCODE_A))
            nativeVisible("Module text echo: a")
            input.text = "native edit works"
            nativeVisible("Module text echo: native edit works")
            requireNotNull(device.findObject(By.desc("Finish module input"))).click()
            rule.waitUntil(5_000) {
                rule.runOnIdle {
                    ViewCompat.getRootWindowInsets(rule.activity.window.decorView)
                        ?.isVisible(WindowInsetsCompat.Type.ime()) != true
                }
            }
            requireNotNull(device.findObject(By.desc("Open native detail"))).click()
            nativeVisible("Independent detail v1")
            device.pressBack()
            nativeVisible("Independent surface v1")
            // Detach and reattach the remote package without reinstalling either APK.
            swipePage(left = false)
            rule.waitUntil(15_000) {
                runCatching {
                    rule.onNode(hasTestTag("launcher-page:home") and
                        hasAnyAncestor(hasTestTag("launcher-pages"))).assertIsDisplayed()
                }.isSuccess
            }
            swipePage(left = true)
            nativeVisible("Independent surface v1")

            val installResult = shell("pm install -r $V2_APK_PATH")
            assertTrue("Module-only update failed: $installResult", installResult.contains("Success"))
            assertEquals(2L, packageManager.getPackageInfo(MODULE_PACKAGE, 0).longVersionCode)
            assertEquals("Core APK changed during module-only update", coreBefore,
                installedApk(context, context.packageName))
            // Activity recreation is a Core process rebind, not a Core package update.
            rule.activityRule.scenario.recreate()
            nativeVisible("Independent surface v2")
            requireNotNull(device.findObject(By.desc("New v2 native control"))).click()
            nativeVisible("New v2 control pressed")
            captureProof("v2.png")
            assertEquals("Core APK changed while rendering v2", coreBefore,
                installedApk(context, context.packageName))
            val after = installedApk(context, context.packageName)
            val proof = org.json.JSONObject().put("coreBeforeSha256", coreBefore.sha256)
                .put("coreAfterSha256", after.sha256).put("coreBeforeUpdatedAt", coreBefore.lastUpdateTime)
                .put("coreAfterUpdatedAt", after.lastUpdateTime).put("moduleBeforeVersion", 1)
                .put("moduleAfterVersion", 2).put("coreUnchanged", coreBefore == after)
            val proofFile = File(requireNotNull(context.getExternalFilesDir("surface-proof")), "core-unchanged.json")
            proofFile.writeText(proof.toString(2))
            shell("cp ${proofFile.absolutePath} /sdcard/Pictures/ModularNativeSurfaces/core-unchanged.json")
            requireNotNull(device.findObject(By.desc("Go to Apps from module"))).click()
            rule.waitUntil(15_000) {
                rule.onAllNodesWithTag("workspace-home-action").fetchSemanticsNodes().isNotEmpty()
            }
        } catch (failure: Throwable) {
            runCatching { captureFailureEvidence("native") }
            throw failure
        } finally {
            addedComponent?.let { savedComponent ->
                runBlocking {
                    val current = store.currentSnapshot()
                    if (current.modulePages.any { it.component == savedComponent }) {
                        store.update(OrganizationBatch(
                            listOf(OrganizationEdit.RemoveModulePage(savedComponent)), current.revision,
                        )).getOrThrow()
                    }
                }
            }
            management.setEnabled(component, wasEnabled)
        }
    }

    private fun openHomeSettings() {
        rule.waitUntil(20_000) { rule.onAllNodesWithTag("home-settings").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("home-settings").performClick()
        rule.onNodeWithTag("settings-open-productivity").performClick()
        rule.onNodeWithTag("settings-open-home").performClick()
        rule.onNodeWithTag("workspace-settings-content").assertIsDisplayed()
    }

    private fun closeHomeSettings() {
        repeat(3) { rule.onNodeWithTag("settings-back").performClick() }
        rule.waitUntil(15_000) { rule.onAllNodesWithTag("launcher-pages").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun nativeVisible(description: String) {
        rule.waitUntil(20_000) { device.hasObject(By.desc(description)) }
        assertTrue("Remote native control missing: $description", device.hasObject(By.desc(description)))
    }

    private fun swipePage(left: Boolean) {
        val width = device.displayWidth
        val y = (device.displayHeight * .42f).toInt()
        assertTrue("Could not inject an OS page swipe", device.swipe(
            (width * (if (left) .82f else .18f)).toInt(), y,
            (width * (if (left) .18f else .82f)).toInt(), y, 40))
        rule.waitForIdle()
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command),
    ).bufferedReader().use { it.readText().trim() }

    private fun captureProof(name: String) {
        val directory = "/sdcard/Pictures/ModularNativeSurfaces"
        val local = File(requireNotNull(instrumentation.targetContext.getExternalFilesDir("surface-proof")), name)
        assertTrue(device.takeScreenshot(local))
        shell("mkdir -p $directory")
        shell("cp ${local.absolutePath} $directory/$name")
    }

    private fun captureFailureEvidence(prefix: String) {
        val directory = "/sdcard/Pictures/ModularNativeSurfaces"
        fun attempt(label: String, action: () -> String) {
            runCatching(action).onSuccess { emitEvidence("$prefix $label", it) }
                .onFailure { emitEvidence("$prefix $label failed", it.toString()) }
        }
        attempt("Core semantics") { rule.onNodeWithTag("launcher-pages").printToString() }
        attempt("logcat") {
            shell("logcat -d -b main -v threadtime -s ModuleWorkspaceSurface:D WorkspaceSurfaceEndpoint:D AndroidRuntime:E")
                .lines().takeLast(120).joinToString("\n")
        }
        attempt("mkdir") { shell("mkdir -p $directory") }
        attempt("screen") {
            shell("screencap -p $directory/$prefix-before-cleanup.png")
            shell("stat -c %s $directory/$prefix-before-cleanup.png")
        }
        attempt("hierarchy") {
            val xml = File(instrumentation.targetContext.cacheDir, "$prefix-before-cleanup.xml")
            device.dumpWindowHierarchy(xml)
            xml.readText()
        }
    }

    private fun emitEvidence(label: String, value: String) {
        value.chunked(2500).ifEmpty { listOf("(empty)") }.forEachIndexed { index, chunk ->
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$label [$index]: $chunk") })
        }
    }

    private fun installedApk(context: Context, packageName: String): ApkFingerprint {
        val info = context.packageManager.getPackageInfo(packageName, 0)
        val apk = requireNotNull(info.applicationInfo?.sourceDir)
        val digest = MessageDigest.getInstance("SHA-256")
        File(apk).inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                digest.update(buffer, 0, size)
            }
        }
        return ApkFingerprint(info.longVersionCode, info.lastUpdateTime, apk,
            digest.digest().joinToString("") { "%02x".format(it) })
    }

    private data class ApkFingerprint(
        val version: Long, val lastUpdateTime: Long, val sourceDir: String, val sha256: String,
    )

    companion object {
        private const val MODULE_PACKAGE = "io.github.alexanderwilhelmsenberg.modular.testsurface"
        private const val V2_APK_PATH = "/data/local/tmp/modular-surface-v2.apk"
    }
}
