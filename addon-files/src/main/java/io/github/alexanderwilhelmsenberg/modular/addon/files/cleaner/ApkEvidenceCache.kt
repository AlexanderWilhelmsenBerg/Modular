package io.github.alexanderwilhelmsenberg.modular.addon.files.cleaner

import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Identity/metadata keyed hints. Reviews still revalidate the live file revision before mutation. */
internal class ApkEvidenceCache(private val directory: File) {
    private var writes = 0
    private fun file(key: String): File = File(directory, MessageDigest.getInstance("SHA-256")
        .digest(key.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) } + ".json")
    fun get(key: String): CleanerEngine.ApkEvidence? = runCatching {
        val j = JSONObject(String(AtomicFile(file(key)).readFully()))
        fun text(name: String): String? = if (j.isNull(name)) null else j.getString(name)
        CleanerEngine.ApkEvidence(text("package"), text("signer"), if (j.isNull("code")) null else j.getLong("code"),
            text("version"), text("architecture"), text("channel"), text("split"), j.getBoolean("debug"))
    }.getOrNull()
    fun put(key: String, value: CleanerEngine.ApkEvidence) {
        directory.mkdirs()
        val j = JSONObject().put("package", value.packageName() ?: JSONObject.NULL)
            .put("signer", value.signer() ?: JSONObject.NULL).put("code", value.versionCode() ?: JSONObject.NULL)
            .put("version", value.versionName() ?: JSONObject.NULL).put("architecture", value.architecture() ?: JSONObject.NULL)
            .put("channel", value.channel() ?: JSONObject.NULL).put("split", value.splitSet() ?: JSONObject.NULL)
            .put("debug", value.debugBuild())
        val storage = AtomicFile(file(key)); val stream = storage.startWrite()
        try { stream.write(j.toString().toByteArray()); storage.finishWrite(stream) }
        catch (failure: Exception) { storage.failWrite(stream); throw failure }
        if (++writes % 64 == 0) directory.listFiles()?.filter { it.extension == "json" }
            ?.sortedByDescending(File::lastModified)?.drop(1000)?.forEach { it.delete() }
    }
}
