package io.github.alexanderwilhelmsenberg.modular.addon.sort.mostused

import android.util.AtomicFile
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppKeyParcel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/** Add-on private, atomic store. A malformed file is an error, never an empty pin list. */
internal class PinStore(directory: File) {
    private val base = directory.resolve("featured_pins").also { if (!it.isDirectory && !it.mkdirs()) throw IOException("Pin directory unavailable") }

    fun read(instanceId: String): PinState {
        val file = fileFor(instanceId)
        val atomic = AtomicFile(file)
        val bytes = try {
            atomic.openRead().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_FILE_BYTES) throw IOException("Pin file exceeds limit")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } catch (missing: FileNotFoundException) {
            if (!file.exists() && !File(file.path + ".bak").exists()) return PinState()
            throw IOException("Pin file unavailable", missing)
        }
        try {
            val root = JSONObject(bytes.toString(Charsets.UTF_8))
            if (root.getInt("schemaVersion") != SCHEMA_VERSION) throw IOException("Unsupported pin schema")
            val revision = root.getLong("revision")
            val array = root.getJSONArray("pins")
            if (array.length() > PinState.MAX_PINS) throw IOException("Too many saved pins")
            val keys = (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                AppKeyParcel(
                    packageName = item.getString("packageName"),
                    activityName = item.getString("activityName"),
                    profileSerial = item.getLong("profileSerial"),
                ).also { if (!validKey(it)) throw IOException("Invalid saved pin") }
            }
            return PinState(revision, keys)
        } catch (error: IOException) {
            throw error
        } catch (error: Exception) {
            throw IOException("Saved pins are malformed", error)
        }
    }

    fun write(instanceId: String, state: PinState) {
        val array = JSONArray()
        state.keys.forEach { key ->
            array.put(JSONObject().put("packageName", key.packageName)
                .put("activityName", key.activityName).put("profileSerial", key.profileSerial))
        }
        val bytes = JSONObject().put("schemaVersion", SCHEMA_VERSION)
            .put("revision", state.revision).put("pins", array)
            .toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_FILE_BYTES) throw IOException("Pin file exceeds limit")
        val atomic = AtomicFile(fileFor(instanceId))
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            output.fd.sync()
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw IOException("Could not save pins", error)
        }
    }

    private fun fileFor(instanceId: String): File {
        require(instanceId.isNotBlank() && instanceId.length <= 200)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(instanceId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return base.resolve("$digest.json")
    }

    companion object {
        private const val SCHEMA_VERSION = 1
        private const val MAX_FILE_BYTES = 32 * 1024

        fun validKey(key: AppKeyParcel): Boolean =
            key.packageName.isNotBlank() && key.packageName.length <= 255 &&
                key.activityName.isNotBlank() && key.activityName.length <= 512 &&
                key.profileSerial >= 0
    }
}
