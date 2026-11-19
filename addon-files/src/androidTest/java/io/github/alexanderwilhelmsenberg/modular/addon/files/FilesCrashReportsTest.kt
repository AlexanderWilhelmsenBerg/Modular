package io.github.alexanderwilhelmsenberg.modular.addon.files

import android.content.Context
import android.content.Intent
import android.os.Process
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FilesCrashReportsTest {
    @Test fun reportRetainsCauseOperationAndHeapInformationAndIsExported() {
        val context = ApplicationProvider.getApplicationContext<FilesApplication>()
        try {
            FilesCrashReports.operation = """{"id":"test-scan","stage":"apk_metadata"}"""
            FilesCrashReports.capture(context, Thread.currentThread(),
                IllegalStateException("outer", IllegalArgumentException("inner cause")))
            val report = FilesCrashReports.read(context)!!
            assertTrue(report.getString("stack").contains("inner cause"))
            assertEquals("apk_metadata", report.getJSONObject("operation").getString("stage"))
            assertTrue(report.getLong("heapLimit") > 0)
            val controller = FilesPageController(context, context.repository)
            val exported = JSONObject(controller.exportEventLogJson())
            assertEquals(report.getString("id"), exported.getJSONObject("lastUnhandledCrash").getString("id"))
            assertTrue(exported.getJSONArray("entries").toString().contains("process.uncaught_exception"))
        } finally { FilesCrashReports.operation = null; FilesCrashReports.clear(context) }
    }

    @Test fun realJavaCrashInSecondaryProcessLeavesItsFullExceptionOnDisk() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        FilesCrashReports.clear(context)
        try {
            ActivityScenario.launch(FilesActivity::class.java).use { scenario ->
                scenario.onActivity { it.startService(Intent(it, FilesCrashProbeService::class.java)) }
                val deadline = System.currentTimeMillis() + 15_000
                while (FilesCrashReports.read(context) == null && System.currentTimeMillis() < deadline) Thread.sleep(50)
                val report = FilesCrashReports.read(context)!!
                assertTrue(report.getString("stack").contains("Files isolated crash capture probe"))
                assertTrue(report.getString("process").endsWith(":crash_probe"))
                assertNotEquals(Process.myPid(), report.getInt("pid"))
            }
        } finally { FilesCrashReports.clear(context) }
    }
}
