package io.github.alexanderwilhelmsenberg.modular.addon.files

import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import io.github.alexanderwilhelmsenberg.modular.addon.files.cleaner.CleanerEngine
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.StorageLocation
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Invoked in two separate instrumentation processes, with force-stop and an Activity launch between. */
class FilesScanProcessRecoveryTest {
    @Test fun preparePendingReadOnlyRequest() {
        val app = ApplicationProvider.getApplicationContext<FilesApplication>()
        app.cleanScanStore.clear()
        app.trashReviewStore.clear()
        val root = app.repository.locations().single { it.kind == StorageLocation.Kind.PHONE }
        val name = "ModularScanRecovery-${UUID.randomUUID()}"
        val folder = app.repository.createDirectory(root.rootId, name)
        val fixture = File(Environment.getExternalStorageDirectory(), name)
        File(app.applicationInfo.sourceDir).copyTo(File(fixture, "sample.apk"))
        repeat(130) { File(fixture, "file-$it.txt").writeText("fixture") }
        val options = CleanerEngine.Options(folder.id, false, emptySet(), emptySet(), 1024, 90,
            java.time.Instant.now(), setOf(CleanerEngine.Category.OLDER_VERSION))
        app.cleanScanStore.begin(name, options)
        File(app.filesDir, "scan-recovery-fixture.json").writeText(JSONObject().put("id", folder.id)
            .put("path", fixture.absolutePath).put("history", JSONArray(app.repository.history().map { it.id }))
            .put("pid", android.os.Process.myPid()).toString())
        app.getSharedPreferences("files-page", 0).edit().clear().putString("folder", folder.id)
            .putString("location", root.id).putString("view", "clean").commit()
        assertNull(app.cleanScanStore.load()!!.analysis)
    }

    @Test fun verifyRecoveredResultInAnotherProcess() {
        val app = ApplicationProvider.getApplicationContext<FilesApplication>()
        val marker = File(app.filesDir, "scan-recovery-fixture.json")
        val fixture = JSONObject(marker.readText())
        try {
            assertNotEquals(fixture.getInt("pid"), android.os.Process.myPid())
            val saved = app.cleanScanStore.load()!!
            assertNotNull("Fresh Activity process did not complete the saved scan", saved.analysis)
            assertEquals(fixture.getString("id"), saved.options.scopeId())
            assertEquals(131, saved.analysis!!.entries().values.count { it.kind() == CleanerEngine.Kind.FILE })
            assertEquals(app.packageName, saved.analysis.entries().values.single { it.apk() != null }.apk().packageName())
            val history = fixture.getJSONArray("history")
            assertEquals((0 until history.length()).map(history::getString).toSet(), app.repository.history().map { it.id }.toSet())
            app.pageController.openCurrentOperation("clean:recovered")
            val page = JSONObject(app.pageController.getPage("""{"version":1,"pageSize":100}"""))
            assertEquals("clean", page.getString("view"))
            assertFalse(page.has("activeOperation"))
        } finally {
            val own = File(fixture.getString("path"))
            require(own.name.startsWith("ModularScanRecovery-") && own.parentFile == Environment.getExternalStorageDirectory())
            own.deleteRecursively(); marker.delete(); app.cleanScanStore.clear()
            app.getSharedPreferences("files-page", 0).edit().clear().commit()
        }
    }
}
