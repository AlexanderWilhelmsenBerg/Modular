package io.github.alexanderwilhelmsenberg.modular.addon.files

import android.app.NotificationManager
import android.content.Context
import android.os.Environment
import android.os.ParcelFileDescriptor
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.github.alexanderwilhelmsenberg.modular.addon.files.cleaner.CleanerEngine
import io.github.alexanderwilhelmsenberg.modular.addon.files.cleaner.CleanerRepositoryAdapter
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class FilesBackgroundScanTest {
    @Test fun realOlderVersionsScanCompletesWithNotificationWhileHomeAndScreenAreOff() {
        val app = ApplicationProvider.getApplicationContext<FilesApplication>()
        val base = app.repository
        val root = base.locations().single { it.kind == StorageLocation.Kind.PHONE }
        val name = "ModularBackground-${UUID.randomUUID()}"
        val folder = base.createDirectory(root.rootId, name)
        val fixture = File(Environment.getExternalStorageDirectory(), name)
        val cache = File(app.cacheDir, "background-scan-${UUID.randomUUID()}.json")
        val store = CleanScanStore(cache)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val repository = object : FilesRepository by base {
            override fun locations() = listOf(root.copy(rootId = folder.id))
            override fun list(folderId: String, offset: Int, limit: Int, sort: FileSort, showHidden: Boolean): FilePage {
                if (folderId == folder.id && offset == 100) {
                    entered.countDown(); check(release.await(30, TimeUnit.SECONDS))
                }
                return base.list(folderId, offset, limit, sort, showHidden)
            }
        }
        app.getSharedPreferences("files-page", 0).edit().clear()
            .putStringSet("cleanChecks", setOf("OLDER_VERSION")).putBoolean("cleanSubfolders", false).commit()
        val controller = FilesPageController(app, repository, app.operationLifetime, null, store)
        try {
            File(app.applicationInfo.sourceDir).copyTo(File(fixture, "000-v1.apk"))
            File(app.applicationInfo.sourceDir).copyTo(File(fixture, "001-v2.apk"))
            File(app.applicationInfo.sourceDir).copyTo(File(fixture, "zzz-v3.apk"))
            repeat(605) { File(fixture, "note-$it.txt").writeText("fixture") }
            ActivityScenario.launch(FilesActivity::class.java).use { scenario ->
                controller.addedLocation(root.id)
                action(controller, "Clean"); action(controller, "Start scan")
                assertTrue("Real scan did not reach its second page", entered.await(20, TimeUnit.SECONDS))
                scenario.onActivity { activity -> activity.setContent {
                    MaterialTheme { FilesNativeContent(controller, 0, {}, {}) }
                } }
                val manager = app.getSystemService(NotificationManager::class.java)
                await { manager.activeNotifications.any { it.id == FilesOperationService.NOTIFICATION && it.isOngoing } }
                device.pressHome(); device.sleep()
                assertFalse(device.isScreenOn)
                assertNotNull(app.operationLifetime.state)
                release.countDown()
                await { store.load()?.analysis != null && app.operationLifetime.state == null }
                val analysis = store.load()!!.analysis!!
                assertEquals(3, analysis.entries().values.count { it.apk()?.packageName() == app.packageName })
                assertEquals(608, analysis.entries().values.count { it.kind() == CleanerEngine.Kind.FILE })
                assertEquals("Scan must not mutate any source", 608, fixture.listFiles()!!.size)
                assertTrue(manager.activeNotifications.any { it.id == FilesNotifications.RESULT && !it.isOngoing })
                val clean = page(controller)
                assertEquals("clean", clean.getString("view"))
                val row = clean.getJSONArray("items").getJSONObject(0)
                assertTrue(row.getString("name").endsWith(".apk"))
                assertTrue(row.getJSONArray("sampleNames").length() >= 1)
                assertEquals(row.getString("name"), JSONObject(requireNotNull(controller.lastDisplayPage))
                    .getJSONArray("items").getJSONObject(0).getString("name"))
            }
        } finally {
            release.countDown()
            if (app.operationLifetime.state != null) {
                controller.cancelCurrentOperation("test_cleanup")
                await { app.operationLifetime.state == null }
            }
            device.wakeUp(); device.executeShellCommand("wm dismiss-keyguard")
            store.clear(); fixture.deleteRecursively()
            app.getSystemService(NotificationManager::class.java).cancel(FilesNotifications.RESULT)
            app.getSharedPreferences("files-page", 0).edit().clear().commit()
        }
    }

    @Test fun apkMetadataUsesConfinedPathsAndSurvivesAdapterRecreationWithoutAnotherPayloadCopy() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val base = createFilesRepository(context)
        val root = base.locations().single { it.kind == StorageLocation.Kind.PHONE }
        val name = "ModularApkCache-${UUID.randomUUID()}"
        val folder = base.createDirectory(root.rootId, name)
        val fixture = File(Environment.getExternalStorageDirectory(), name)
        val reads = AtomicInteger(); val parses = AtomicInteger()
        val repository = object : FilesRepository by base {
            override fun openRead(id: String): ParcelFileDescriptor { reads.incrementAndGet(); return base.openRead(id) }
            override fun localMetadataPath(id: String): String? { parses.incrementAndGet(); return base.localMetadataPath(id) }
        }
        try {
            File(context.applicationInfo.sourceDir).copyTo(File(fixture, "sample.apk"))
            val options = CleanerEngine.Options(folder.id, false, emptySet(), emptySet(), 1024, 90,
                java.time.Instant.now(), setOf(CleanerEngine.Category.OLDER_VERSION))
            fun scan() = CleanerRepositoryAdapter(repository, context).snapshot(folder.id, progressiveOptions = options)
            val first = scan().entries.single { it.apk() != null }.apk()
            assertEquals(context.packageName, first.packageName())
            assertEquals(1, parses.get()); assertEquals(0, reads.get())
            assertEquals(first, scan().entries.single { it.apk() != null }.apk())
            assertEquals("Persisted evidence must avoid another parser read", 1, parses.get())
            val apk = File(fixture, "sample.apk")
            assertTrue(apk.setLastModified(apk.lastModified() + 10_000))
            scan()
            assertEquals("Changed revisions must be inspected again", 2, parses.get())
            assertEquals(0, reads.get())
        } finally { fixture.deleteRecursively() }
    }

    private fun page(controller: FilesPageController) = JSONObject(controller.getPage("""{"version":1,"pageSize":100}"""))
    private fun action(controller: FilesPageController, label: String) {
        val items = page(controller).getJSONArray("actions")
        val action = (0 until items.length()).map(items::getJSONObject).single { it.getString("label") == label }
        controller.performAction(JSONObject().put("version", 1).put("actionId", action.getString("id"))
            .put("operationToken", UUID.randomUUID().toString()).toString())
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 60_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue("Background scan did not finish", condition())
    }
}
