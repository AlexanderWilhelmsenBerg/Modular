package io.github.alexanderwilhelmsenberg.modular.widgets

import android.content.Context
import java.io.File
import java.util.UUID

/** Lives outside Android Auto Backup; copied widget IDs cannot bind on another installation. */
internal object WidgetHostToken {
    fun readOrCreate(context: Context): String = synchronized(this) {
        val file = File(context.noBackupFilesDir, "workspace_widget_host_token_v1")
        file.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }?.let { return@synchronized it }
        val token = UUID.randomUUID().toString()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(token)
        check(temporary.renameTo(file)) { "Could not create local widget host identity" }
        token
    }
}

/** Returns this installation's local widget host identity for fixture and recovery flows. */
fun workspaceHostToken(context: Context): String = WidgetHostToken.readOrCreate(context)
