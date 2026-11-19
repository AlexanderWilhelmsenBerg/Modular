package io.github.alexanderwilhelmsenberg.modular.addon.files

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.Environment
import android.os.Build
import android.view.SurfaceControlViewHost
import android.view.SurfaceView
import android.view.View
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.github.alexanderwilhelmsenberg.modular.moduleapi.IModularHost
import io.github.alexanderwilhelmsenberg.modular.moduleapi.WorkspacePageContract
import io.github.alexanderwilhelmsenberg.modular.moduleapi.capabilities.IWorkspaceSurfaceHost
import io.github.alexanderwilhelmsenberg.modular.moduleapi.capabilities.IWorkspaceSurfaceSession
import io.github.alexanderwilhelmsenberg.modular.modulesdk.ModuleInstanceContext
import io.github.alexanderwilhelmsenberg.modular.modulesdk.WorkspaceSurfaceEndpoint
import io.github.alexanderwilhelmsenberg.modular.modulesdk.WorkspaceSurfaceScope
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

/** Real SCVH detach/close and Activity handoffs; deliberately does not depend on Espresso. */
class FilesSurfaceDetachTest {
    private val app get() = ApplicationProvider.getApplicationContext<FilesApplication>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun androidDetachAndRepeatedCloseDoNotInterruptBackgroundScan() {
        val base = app.repository
        val root = base.locations().single { it.kind == StorageLocation.Kind.PHONE }
        val name = "ModularSurface-${UUID.randomUUID()}"
        val folder = base.createDirectory(root.rootId, name)
        val fixture = File(Environment.getExternalStorageDirectory(), name)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val scanStarted = AtomicBoolean(false)
        val saved = CleanScanStore(File(app.cacheDir, "$name.json"))
        val repository = object : FilesRepository by base {
            override fun locations() = listOf(root.copy(rootId = folder.id))
            override fun list(folderId: String, offset: Int, limit: Int, sort: FileSort, showHidden: Boolean): FilePage {
                if (folderId == folder.id && scanStarted.get() && app.operationLifetime.state != null) {
                    entered.countDown(); check(release.await(30, TimeUnit.SECONDS))
                }
                return base.list(folderId, offset, limit, sort, showHidden)
            }
        }
        app.getSharedPreferences("files-page", 0).edit().clear()
            .putStringSet("cleanChecks", setOf("NUMBERED_COPY")).putBoolean("cleanSubfolders", false).commit()
        val controller = FilesPageController(app, repository, app.operationLifetime, null, saved)
        val device = UiDevice.getInstance(instrumentation)
        val closed = AtomicInteger()
        val detached = AtomicInteger()
        try {
            repeat(5) { File(fixture, "file-$it.txt").writeText("test") }
            ActivityScenario.launch(FilesActivity::class.java).use { scenario ->
                val view = hostView(scenario)
                val endpoint = endpoint(closed, detached = detached)
                controller.addedLocation(root.id)
                action(controller, "Clean")
                scanStarted.set(true)
                action(controller, "Start scan")
                assertTrue(entered.await(15, TimeUnit.SECONDS))
                val pid = Process.myPid()
                repeat(4) {
                    val session = attach(endpoint, view)
                    // Core's detach notification releases Android's ViewRoot before SDK close.
                    detachLikeCore(view, session.second)
                    await { detached.get() == it + 1 }
                    session.first.close(); session.first.close()
                    session.second.release()
                    instrumentation.waitForIdleSync()
                    assertEquals(it + 1, closed.get())
                }
                assertEquals(pid, Process.myPid())
                assertNotNull(app.operationLifetime.state)
                device.pressHome(); device.sleep()
                release.countDown()
                await { saved.load()?.analysis != null && app.operationLifetime.state == null }
                assertEquals(5, saved.load()!!.analysis!!.entries().values.count { it.kind().name == "FILE" })
                assertEquals(5, fixture.listFiles()!!.size)
            }
        } finally {
            release.countDown()
            if (app.operationLifetime.state != null) { controller.cancelCurrentOperation("test"); await { app.operationLifetime.state == null } }
            device.wakeUp(); device.executeShellCommand("wm dismiss-keyguard")
            saved.clear(); fixture.deleteRecursively()
            app.getSharedPreferences("files-page", 0).edit().clear().commit()
        }
    }

    @Test fun eventLogDownloadHandoffDetachesFilesSurfaceAndWritesNonemptyJson() {
        val destination = File(File(app.cacheDir, "event-log-exports").apply { mkdirs() }, "test-${UUID.randomUUID()}.json")
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", destination)
        val picked = CountDownLatch(1)
        var session: Pair<IWorkspaceSurfaceSession, SurfaceControlViewHost.SurfacePackage>? = null
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_CREATE_DOCUMENT) return null
                val pending = app.getSharedPreferences("files-log-export", 0).getString("pending", null)
                assertNotNull("JSON must be staged before opening the picker", pending)
                assertTrue(File(app.filesDir, "event-log-exports/$pending").length() > 0)
                session!!.second.notifyDetachedFromWindow()
                Handler(Looper.getMainLooper()).post { session!!.first.close() }
                picked.countDown()
                return Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            ActivityScenario.launch(FilesActivity::class.java).use { scenario ->
                val view = hostView(scenario)
                session = attach(FilesSurfaceEndpoint(app, instance()), view)
                val controller = app.pageController
                controller.openSettings(); action(controller, "Event log")
                val nonce = action(controller, "Download JSON").getJSONObject("hostLaunch").getString("nonce")
                app.startActivity(Intent(app, FilesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(WorkspacePageContract.EXTRA_HOST_HANDOFF_NONCE, nonce))
                assertTrue("Download did not open the create-document contract", picked.await(15, TimeUnit.SECONDS))
                await { destination.length() > 0 && app.getSharedPreferences("files-log-export", 0).getString("pending", null) == null }
                assertTrue(JSONObject(destination.readText()).getJSONArray("entries").length() > 0)
                instrumentation.waitForIdleSync()
            }
        } finally {
            instrumentation.removeMonitor(monitor)
            session?.first?.close(); session?.second?.release()
            app.logExporter.cancelDownload(); destination.delete()
        }
    }

    @Test fun failedViewCreationRetiresSessionAndRunsCloseCallbacksOnce() {
        val closed = AtomicInteger()
        ActivityScenario.launch(FilesActivity::class.java).use { scenario ->
            val view = hostView(scenario)
            val endpoint = endpoint(closed, fail = true)
            repeat(4) {
                assertTrue(runCatching { attach(endpoint, view) }.isFailure)
                instrumentation.waitForIdleSync()
                assertEquals(it + 1, closed.get())
            }
        }
    }

    private fun endpoint(closed: AtomicInteger, fail: Boolean = false, detached: AtomicInteger? = null) = object : WorkspaceSurfaceEndpoint(app, instance()) {
        override fun onCreateView(scope: WorkspaceSurfaceScope): View {
            scope.registerOnClose { closed.incrementAndGet() }
            if (fail) error("intentional renderer setup failure")
            return TextView(scope.context).apply {
                text = "Embedded surface"
                addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(view: View) = Unit
                    override fun onViewDetachedFromWindow(view: View) { detached?.incrementAndGet() }
                })
            }
        }
    }

    private fun instance(): ModuleInstanceContext {
        val host = object : IModularHost.Stub() {
            override fun getHostProtocolVersion() = 4
            override fun getCapabilities() = emptyArray<String>()
            override fun getCapabilityBinder(capabilityId: String): IBinder? = null
        }
        // Test-only same-UID trust fixture; production authentication is unchanged.
        val constructor = ModuleInstanceContext::class.java.declaredConstructors.single { it.parameterCount == 4 }
        constructor.isAccessible = true
        return constructor.newInstance(UUID.randomUUID().toString(), "{}", host,
            { uid: Int -> uid == Process.myUid() }) as ModuleInstanceContext
    }

    private fun hostView(scenario: ActivityScenario<FilesActivity>): SurfaceView {
        lateinit var view: SurfaceView
        scenario.onActivity { activity -> view = SurfaceView(activity); activity.setContentView(view) }
        await { view.hostToken != null && view.width > 0 && view.height > 0 }
        return view
    }

    private fun detachLikeCore(view: SurfaceView, surfacePackage: SurfaceControlViewHost.SurfacePackage) {
        instrumentation.runOnMainSync { if (Build.VERSION.SDK_INT >= 36) view.clearChildSurfacePackage() }
        surfacePackage.notifyDetachedFromWindow()
    }

    private fun attach(endpoint: WorkspaceSurfaceEndpoint, view: SurfaceView): Pair<IWorkspaceSurfaceSession, SurfaceControlViewHost.SurfacePackage> {
        val spec = JSONObject().put("version", 1).put("sessionId", UUID.randomUUID().toString())
            .put("widthPx", view.width).put("heightPx", view.height).put("displayId", view.display.displayId)
            .put("densityDpi", app.resources.displayMetrics.densityDpi).put("fontScale", 1.0)
            .put("visible", true).put("theme", "dark").put("colors", JSONObject()).put("localeTags", "en-US")
        val callback = object : IWorkspaceSurfaceHost.Stub() {
            override fun onStateChanged(sessionId: String, stateJson: String) = Unit
            override fun requestNavigation(sessionId: String, destination: String) = Unit
            override fun requestActivity(sessionId: String, nonce: String) = Unit
        }
        val session = endpoint.createSession(view.hostToken, spec.toString(), callback)
        val surfacePackage = session.surfacePackage!!
        instrumentation.runOnMainSync { view.setChildSurfacePackage(surfacePackage) }
        instrumentation.waitForIdleSync()
        return session to surfacePackage
    }

    private fun action(controller: FilesPageController, label: String): JSONObject {
        val actions = JSONObject(controller.getPage("""{"version":1,"pageSize":100}""")).getJSONArray("actions")
        val command = (0 until actions.length()).map(actions::getJSONObject).single { it.getString("label") == label }
        return JSONObject(controller.performAction(JSONObject().put("version", 1).put("actionId", command.getString("id"))
            .put("operationToken", UUID.randomUUID().toString()).toString()))
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue("Surface regression check timed out", condition())
    }
}
