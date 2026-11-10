package io.github.alexanderwilhelmsenberg.modular.addon.files

import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.net.Uri
import android.content.ClipData
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.alexanderwilhelmsenberg.modular.filesui.FilesPage
import io.github.alexanderwilhelmsenberg.modular.moduleapi.WorkspacePageContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Standalone setup and file browser share the same trusted renderer as Core. */
open class FilesActivity : ComponentActivity() {
    private lateinit var controller: FilesPageController
    private var launchedFromCoreHandoff = false
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
                            .getOrElse { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                Intent.FLAG_GRANT_READ_URI_PERMISSION }
                        val location = (application as FilesApplication).repository.addSelectedFolder(uri, flags)
                        controller.addedLocation(location.id)
                    }
                }
                result.exceptionOrNull()?.let { Toast.makeText(this@FilesActivity,
                    it.message ?: "Folder access could not be saved", Toast.LENGTH_LONG).show() }
                refresh?.invoke()
                if (launchedFromCoreHandoff) finish()
            }
    }
    private var refresh: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = (application as FilesApplication).pageController
        if (intent?.getBooleanExtra(EXTRA_SETTINGS, false) == true) controller.openSettings()
        val handoff = if (savedInstanceState == null) intent?.getStringExtra(WorkspacePageContract.EXTRA_HOST_HANDOFF_NONCE)
            ?.let(controller::consumeHostHandoff) else null
        if (savedInstanceState == null) {
            if (handoff != null) {
                launchedFromCoreHandoff = true
                window.decorView.post { handleHandoff(handoff, true) }
            }
        }
        setContent {
            var page by remember { mutableStateOf<String?>(null) }
            var error by remember { mutableStateOf<String?>(null) }
            val scope = rememberCoroutineScope()
            fun load() {
                scope.launch {
                    page = withContext(Dispatchers.IO) {
                        runCatching { controller.getPage("{\"version\":1,\"pageSize\":100}") }
                            .onFailure { error = it.message }.getOrNull()
                    }
                }
            }
            refresh = ::load
            LaunchedEffect(Unit) { load() }
            val themeName = getSharedPreferences("files-page", MODE_PRIVATE).getString("theme", "system") ?: "system"
            val dark = themeName == "dark" || themeName == "amoled" || (themeName == "system" && isSystemInDarkTheme())
            MaterialTheme(colorScheme = if (themeName == "amoled") darkColorScheme(background = Color.Black,
                surface = Color.Black, surfaceContainer = Color(0xFF101010))
                else if (dark) darkColorScheme() else lightColorScheme()) {
                if (page == null) {
                    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(error ?: "Loading Files…")
                        Button(onClick = { chooseFolder.launch(null) }) { Text("Choose folder") }
                    }
                } else FilesPage(page!!, documentLoader = { id, mode ->
                    withContext(Dispatchers.IO) { controller.openDocument(id, mode) }
                }, documentCommit = { request ->
                    val response = withContext(Dispatchers.IO) { controller.performAction(request) }
                    page = response
                    JSONObject(response).optString("documentSaveStatus") == "saved"
                }, documentCancel = { request ->
                    page = withContext(Dispatchers.IO) { controller.performAction(request) }
                }, onAction = { request ->
                    scope.launch {
                        page = withContext(Dispatchers.IO) {
                            runCatching { controller.performAction(request) }
                                .onFailure { error = it.message }.getOrNull()
                        } ?: page
                        page?.let { response ->
                            val host = JSONObject(response).optJSONObject("hostLaunch")
                            host?.optString("nonce")?.let(controller::consumeHostHandoff)?.let {
                                handleHandoff(it, false)
                            }
                        }
                    }
                }, onOpenDocument = { documentId, _ -> openOrShare(documentId, false) })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh?.invoke()
    }

    private fun openOrShare(documentId: String, share: Boolean, finishAfter: Boolean = false) {
        lifecycleScope.launch {
            val result = runCatching {
                val content = withContext(Dispatchers.IO) {
                    (application as FilesApplication).repository.scopedContent(documentId)
                }
                if (share) startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND)
                    .setType(content.mimeType ?: "application/octet-stream")
                    .putExtra(Intent.EXTRA_STREAM, content.uri)
                    .setClipData(ClipData.newUri(contentResolver, "Shared file", content.uri))
                    .addFlags(content.grantFlags or Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share file"))
                else startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(content.uri,
                    content.mimeType ?: "application/octet-stream")
                    .setClipData(ClipData.newUri(contentResolver, "Opened file", content.uri))
                    .addFlags(content.grantFlags or Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }
            result.exceptionOrNull()?.let { Toast.makeText(this@FilesActivity,
                it.message ?: "No compatible app could open this file", Toast.LENGTH_LONG).show() }
            if (finishAfter && result.isSuccess) finish()
        }
    }

    private fun handleHandoff(handoff: FilesPageController.HostHandoff, fromCore: Boolean) {
        when (handoff.kind) {
            "pickFolder" -> chooseFolder.launch(null)
            "fullAccess" -> {
                if (fromCore) { controller.openSettings(); refresh?.invoke() }
                else if (Build.VERSION.SDK_INT >= 30) startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")))
            }
            "openDocument" -> handoff.documentId?.let { openOrShare(it, false, fromCore) }
            "shareDocument" -> handoff.documentId?.let { openOrShare(it, true, fromCore) }
        }
    }

    private fun findAction(page: String?, id: String): JSONObject? {
        if (page == null || id.isBlank()) return null
        val root = runCatching { JSONObject(page) }.getOrNull() ?: return null
        fun match(array: org.json.JSONArray?): JSONObject? = (0 until (array?.length() ?: 0))
            .mapNotNull { array?.optJSONObject(it) }.firstOrNull { it.optString("id") == id }
        match(root.optJSONArray("actions"))?.let { return it }
        val items = root.optJSONArray("items")
        for (i in 0 until (items?.length() ?: 0)) {
            match(items?.optJSONObject(i)?.optJSONArray("actions"))?.let { return it }
        }
        return null
    }

    companion object {
        const val EXTRA_SETTINGS = "openFilesSettings"
    }
}
