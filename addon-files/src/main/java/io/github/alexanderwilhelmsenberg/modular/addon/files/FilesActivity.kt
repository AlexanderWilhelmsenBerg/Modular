package io.github.alexanderwilhelmsenberg.modular.addon.files

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import io.github.alexanderwilhelmsenberg.modular.moduleapi.WorkspacePageContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Standalone browser and the declared host handoff target. */
open class FilesActivity : ComponentActivity() {
    private lateinit var controller: FilesPageController
    private var launchedFromCoreHandoff = false
    private var refreshToken by mutableIntStateOf(0)
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        controller.recordLifecycleEvent("notification.permission", if (granted) "Progress notifications enabled" else "Notification permission declined")
        refreshToken++
        if (launchedFromCoreHandoff) finish()
    }

    private val saveEventLog = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) {
            (application as FilesApplication).logExporter.cancelDownload()
            if (launchedFromCoreHandoff) finish()
            return@registerForActivityResult
        }
        val app = application as FilesApplication
        app.logExporter.export(uri) { result ->
            result.onSuccess { controller.recordLifecycleEvent("eventLog.export_completed", "Saved ${it.length()} bytes of event log JSON") }
            result.exceptionOrNull()?.let { controller.recordUiFailure("Download event log", it) }
            android.os.Handler(mainLooper).post {
                Toast.makeText(app,
                    result.fold({ "Event log saved" }, { it.message ?: "Could not save the event log" }), Toast.LENGTH_LONG).show()
                if (!isDestroyed && launchedFromCoreHandoff) finish()
            }
        }
    }

    private val chooseFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) {
            if (launchedFromCoreHandoff) finish()
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val full = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    val flags = runCatching { contentResolver.takePersistableUriPermission(uri, full); full }
                        .getOrElse {
                            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        }
                    val location = (application as FilesApplication).repository.addSelectedFolder(uri, flags)
                    controller.addedLocation(location.id)
                }
            }
            result.exceptionOrNull()?.let {
                Toast.makeText(this@FilesActivity, it.message ?: "Folder access could not be saved", Toast.LENGTH_LONG).show()
            }
            refreshToken++
            if (launchedFromCoreHandoff) finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = (application as FilesApplication).pageController
        if (intent?.getBooleanExtra(EXTRA_SHOW_OPERATION, false) == true) controller.openCurrentOperation(intent.getStringExtra("operationId"))
        launchedFromCoreHandoff = savedInstanceState?.getBoolean("coreHandoff") == true
        if (intent?.getBooleanExtra(EXTRA_SETTINGS, false) == true) controller.openSettings()
        val handoff = if (savedInstanceState == null) intent?.getStringExtra(WorkspacePageContract.EXTRA_HOST_HANDOFF_NONCE)
            ?.let(controller::consumeHostHandoff) else null
        if (handoff != null) {
            launchedFromCoreHandoff = true
            window.decorView.post { handleHandoff(handoff, true) }
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
        setContent {
            val themeName = getSharedPreferences("files-page", MODE_PRIVATE).getString("theme", "system") ?: "system"
            val dark = themeName == "dark" || themeName == "amoled" ||
                (themeName == "system" && isSystemInDarkTheme())
            MaterialTheme(colorScheme = if (themeName == "amoled") darkColorScheme(
                background = Color.Black, surface = Color.Black, surfaceContainer = Color(0xFF101010))
            else if (dark) darkColorScheme() else lightColorScheme()) {
                SideEffect {
                    WindowInsetsControllerCompat(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
                    FilesNativeContent(controller, refreshToken,
                        onHostLaunch = { nonce ->
                            controller.consumeHostHandoff(nonce)?.let { handleHandoff(it, false) }
                        },
                        onExternalDocument = { id -> openOrShare(id, false) })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_SHOW_OPERATION, false)) controller.openCurrentOperation(intent.getStringExtra("operationId"))
        refreshToken++
    }

    private fun openOrShare(documentId: String, share: Boolean, finishAfter: Boolean = false) {
        lifecycleScope.launch {
            val result = runCatching {
                val content = withContext(Dispatchers.IO) {
                    (application as FilesApplication).repository.scopedContent(documentId)
                }
                if (share) startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = content.mimeType ?: "application/octet-stream"
                    putExtra(Intent.EXTRA_STREAM, content.uri)
                    clipData = ClipData.newUri(contentResolver, "Shared file", content.uri)
                    addFlags(content.grantFlags or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Share file"))
                else startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(content.uri, content.mimeType ?: "application/octet-stream")
                    clipData = ClipData.newUri(contentResolver, "Opened file", content.uri)
                    addFlags(content.grantFlags or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
            }
            result.exceptionOrNull()?.let {
                Toast.makeText(this@FilesActivity, it.message ?: "No compatible app could open this file", Toast.LENGTH_LONG).show()
            }
            if (finishAfter && result.isSuccess) finish()
        }
    }

    private fun handleHandoff(handoff: FilesPageController.HostHandoff, fromCore: Boolean) {
        when (handoff.kind) {
            "startOperation" -> handoff.documentId?.let { id ->
                val app = application as FilesApplication
                app.operationLifetime.retryFromActivity(id)
                Thread({
                    val result = runCatching { app.operationLifetime.awaitReady(id,
                        io.github.alexanderwilhelmsenberg.modular.addon.files.storage.Cancellation { false }) }
                    result.exceptionOrNull()?.let { controller.recordUiFailure("Start background operation", it) }
                    android.os.Handler(mainLooper).post { if (!isDestroyed && fromCore) finish() }
                }, "files-operation-start").start()
            }
            "shareEventLog" -> (application as FilesApplication).logExporter.prepare { result ->
                result.exceptionOrNull()?.let { controller.recordUiFailure("Share event log", it) }
                android.os.Handler(mainLooper).post {
                    if (!isDestroyed) result.onSuccess { file ->
                        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", file)
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/json")
                            .putExtra(Intent.EXTRA_STREAM, uri).apply {
                                clipData = ClipData.newUri(contentResolver, "Event log", uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }, "Share event log"))
                        if (fromCore) finish()
                    }.onFailure { Toast.makeText(this, it.message, Toast.LENGTH_LONG).show() }
                }
            }
            "exportEventLog" -> {
                val app = application as FilesApplication
                app.logExporter.prepareDownload { result ->
                    android.os.Handler(mainLooper).post {
                        if (isDestroyed) return@post
                        result.onSuccess {
                            controller.recordLifecycleEvent("eventLog.export_prepared", "Prepared ${it.length()} bytes before opening the save picker")
                            saveEventLog.launch("modular-files-events-${java.time.LocalDate.now()}.json")
                        }.onFailure {
                            controller.recordUiFailure("Prepare event log download", it)
                            Toast.makeText(app, it.message ?: "Could not prepare the event log", Toast.LENGTH_LONG).show()
                            if (fromCore) finish()
                        }
                    }
                }
            }
            "notifications" -> {
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
                    (!getSharedPreferences("files-page", MODE_PRIVATE).getBoolean("notificationsRequested", false) ||
                        shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS))) {
                    getSharedPreferences("files-page", MODE_PRIVATE).edit().putBoolean("notificationsRequested", true).apply()
                    notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                    if (fromCore) finish()
                }
            }
            "pickFolder" -> chooseFolder.launch(null)
            "editDocument" -> {
                // The controller remains on the active Markdown page. This Activity supplies
                // the result registry for Save as; its shared content reloads the private draft.
                refreshToken++
            }
            "fullAccess" -> {
                if (Build.VERSION.SDK_INT >= 30) {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")))
                }
                if (fromCore) finish()
            }
            "openDocument" -> handoff.documentId?.let { openOrShare(it, false, fromCore) }
            "shareDocument" -> handoff.documentId?.let { openOrShare(it, true, fromCore) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("coreHandoff", launchedFromCoreHandoff)
        super.onSaveInstanceState(outState)
    }

    companion object {
        const val EXTRA_SETTINGS = "openFilesSettings"
        const val EXTRA_SHOW_OPERATION = "showFilesOperation"
    }
}
