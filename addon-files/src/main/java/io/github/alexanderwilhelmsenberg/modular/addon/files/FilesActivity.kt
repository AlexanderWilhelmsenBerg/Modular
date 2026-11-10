package io.github.alexanderwilhelmsenberg.modular.addon.files

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Standalone setup and file browser share the same trusted renderer as Core. */
open class FilesActivity : ComponentActivity() {
    private lateinit var controller: FilesPageController
    private val chooseFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching {
                contentResolver.takePersistableUriPermission(uri, flags)
                val location = (application as FilesApplication).repository.addSelectedFolder(uri, flags)
                controller.addedLocation(location.id)
            }
            refresh?.invoke()
        }
    }
    private var refresh: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = (application as FilesApplication).pageController
        if (intent?.getBooleanExtra(EXTRA_SETTINGS, false) == true) controller.openSettings()
        if (savedInstanceState == null && intent?.getBooleanExtra(EXTRA_PICK_FOLDER, false) == true) {
            window.decorView.post { chooseFolder.launch(null) }
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
                    }
                }, onOpenDocument = { documentId, _ ->
                    runCatching {
                        val scoped = (application as FilesApplication).repository.scopedContent(documentId)
                        startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(scoped.uri, scoped.mimeType ?: "*/*")
                            .addFlags(scoped.grantFlags or Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    }
                })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh?.invoke()
    }

    companion object {
        const val EXTRA_SETTINGS = "openFilesSettings"
        const val EXTRA_PICK_FOLDER = "pickFilesFolder"
    }
}
