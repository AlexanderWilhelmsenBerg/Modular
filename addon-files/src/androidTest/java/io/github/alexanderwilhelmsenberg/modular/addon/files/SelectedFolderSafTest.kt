package io.github.alexanderwilhelmsenberg.modular.addon.files

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.StorageLocation
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.createFilesRepository
import java.io.File
import java.io.FileInputStream
import java.util.UUID
import java.util.regex.Pattern
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the real API 35 system DocumentsUI to obtain a persisted tree grant. */
@RunWith(AndroidJUnit4::class)
class SelectedFolderSafTest {
    @Test fun pickedFolderRemainsUsableWithoutBroadAccessAndRevocationReconnects() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val device = UiDevice.getInstance(instrumentation)
        val name = "ModularFilesValidation-${UUID.randomUUID()}"
        val fixture = File(EnvironmentPath.downloads(), name)
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val originalMode = appOps.unsafeCheckOpNoThrow(
            "android:manage_external_storage", Process.myUid(), context.packageName,
        )
        var locationId: String? = null
        var scenario: ActivityScenario<FilesActivity>? = null
        try {
            shell("appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE ignore")
            assertTrue("Could not create disposable Downloads child",
                shell("mkdir -p -- '${fixture.absolutePath}' && echo READY").contains("READY"))
            scenario = ActivityScenario.launch(
                Intent(context, FilesActivity::class.java).putExtra(FilesActivity.EXTRA_PICK_FOLDER, true),
            )
            val downloads = device.wait(Until.findObject(By.text("Downloads")), 10_000)
                ?: run {
                    device.findObject(By.desc(Pattern.compile("(?i).*roots.*|.*navigation.*|.*menu.*")))?.click()
                    device.wait(Until.findObject(By.text("Downloads")), 10_000)
                }
            requireNotNull(downloads) { "System tree picker did not show Downloads" }.click()
            val folder = device.wait(Until.findObject(By.text(name)), 10_000)
            requireNotNull(folder) { "System tree picker did not show test folder $name" }.click()
            val choose = device.wait(Until.findObject(By.text(Pattern.compile("(?i)use this folder"))), 10_000)
            requireNotNull(choose) { "System tree picker did not show Use this folder" }.click()
            device.wait(Until.findObject(By.text(Pattern.compile("(?i)allow"))), 5_000)?.click()

            val deadline = System.currentTimeMillis() + 15_000
            var selected: StorageLocation? = null
            while (selected == null && System.currentTimeMillis() < deadline) {
                selected = createFilesRepository(context).locations().firstOrNull {
                    it.kind == StorageLocation.Kind.SELECTED_FOLDER && it.title == name && it.available
                }
                if (selected == null) Thread.sleep(100)
            }
            val location = requireNotNull(selected) { "Picker grant was not persisted by Files" }
            locationId = location.id
            val repo = createFilesRepository(context)
            assertFalse("Selected-folder test unexpectedly has broad access", android.os.Environment.isExternalStorageManager())
            val document = repo.createFile(location.rootId, "hello.md", "text/markdown")
            assertTrue(document.readable)
            val fingerprint = repo.fingerprint(document.id)
            repo.openWrite(document.id, fingerprint).use { descriptor ->
                ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { it.write("# After\n".toByteArray()) }
            }
            val read = repo.openRead(document.id).use { descriptor ->
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
            }
            assertEquals("# After\n", read)

            requireNotNull(scenario).recreate()
            assertTrue(createFilesRepository(context).locations().any { it.id == location.id && it.available })
            val grant = context.contentResolver.persistedUriPermissions.single { it.uri.toString().contains(name) }
            context.contentResolver.releasePersistableUriPermission(grant.uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            val disconnected = createFilesRepository(context).locations().single { it.id == location.id }
            assertFalse(disconnected.available)
            val page = JSONObject(FilesPageController(context).getPage("""{"version":1,"pageSize":100}"""))
            assertEquals("unavailable", page.getString("state"))
            assertEquals(location.id, page.getString("locationId"))
            assertTrue(page.getString("summary").contains("Remembered location"))
        } finally {
            scenario?.close()
            locationId?.let { createFilesRepository(context).removeSelectedFolder(it) }
            context.getSharedPreferences("files-page", 0).edit().clear().commit()
            if (fixture.name == name && fixture.parentFile?.canonicalFile ==
                EnvironmentPath.downloads().canonicalFile) shell("rm -rf -- '${fixture.absolutePath}'")
            shell("appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE ${modeName(originalMode)}")
        }
    }

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return try { FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() } }
        finally { descriptor.close() }
    }

    private fun modeName(mode: Int) = when (mode) {
        AppOpsManager.MODE_ALLOWED -> "allow"
        AppOpsManager.MODE_IGNORED -> "ignore"
        AppOpsManager.MODE_ERRORED -> "deny"
        AppOpsManager.MODE_DEFAULT -> "default"
        AppOpsManager.MODE_FOREGROUND -> "foreground"
        else -> error("Unknown original All files access mode: $mode")
    }

    private object EnvironmentPath {
        fun downloads(): File = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS,
        )
    }
}
