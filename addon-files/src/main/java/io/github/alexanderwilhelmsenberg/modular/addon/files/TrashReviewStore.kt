package io.github.alexanderwilhelmsenberg.modular.addon.files

import android.util.AtomicFile
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.EmptyTrashPlan
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.FileEntry
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.TrashEntry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Only read-only preparation is resumable. This file never authorizes deletion. */
internal class TrashReviewStore(file: File) {
    data class Saved(val id: String, val ids: List<String>, val offline: Int, val plan: EmptyTrashPlan? = null)
    private val storage = AtomicFile(file)
    private var loaded = false
    private var cached: Saved? = null
    var error: String? = null
        private set

    @Synchronized fun load(): Saved? {
        if (loaded) return cached
        loaded = true
        if (!storage.baseFile.exists() && !File(storage.baseFile.path + ".bak").exists()) return null
        cached = runCatching {
            val json = JSONObject(String(storage.readFully(), Charsets.UTF_8))
            require(json.getInt("version") == 1)
            val plan = json.optJSONObject("plan")?.let { p ->
                EmptyTrashPlan(p.getJSONArray("entries").objects().map(::trash),
                    p.getJSONArray("descendants").objects().map(::entry), p.getBoolean("complete"),
                    p.getJSONArray("errors").strings(), p.getJSONObject("fingerprints").let { f ->
                        f.keys().asSequence().associateWith { f.getString(it) }
                    })
            }
            Saved(json.getString("id"), json.getJSONArray("ids").strings(), json.getInt("offline"), plan)
        }.getOrElse { error = it.message ?: "Saved trash review is unreadable"; null }
        return cached
    }

    @Synchronized fun begin(ids: List<String>, offline: Int): Saved =
        Saved(UUID.randomUUID().toString(), ids.distinct(), offline).also(::write)

    @Synchronized fun complete(id: String, plan: EmptyTrashPlan) {
        load()?.takeIf { it.id == id }?.let { write(it.copy(plan = plan)) }
    }

    @Synchronized fun clear(id: String? = null) {
        if (id != null && load()?.id != id) return
        storage.delete(); cached = null; loaded = true; error = null
    }

    private fun write(saved: Saved) {
        val json = JSONObject().put("version", 1).put("id", saved.id).put("ids", JSONArray(saved.ids))
            .put("offline", saved.offline)
        saved.plan?.let { plan ->
            json.put("plan", JSONObject().put("entries", JSONArray(plan.entries.map(::trashJson)))
                .put("descendants", JSONArray(plan.descendants.map(::entryJson)))
                .put("complete", plan.complete).put("errors", JSONArray(plan.errors))
                .put("fingerprints", JSONObject(plan.fingerprints)))
        }
        storage.baseFile.parentFile?.mkdirs()
        val stream = storage.startWrite()
        try {
            stream.write(json.toString().toByteArray(Charsets.UTF_8)); storage.finishWrite(stream)
            cached = saved; loaded = true; error = null
        } catch (failure: Exception) { storage.failWrite(stream); throw failure }
    }

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    private fun JSONArray.strings() = (0 until length()).map { getString(it) }
    private fun JSONObject.longOrNull(key: String): Long? = if (isNull(key)) null else getLong(key)
    private fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else getString(key)
    private fun trashJson(t: TrashEntry) = JSONObject().put("id", t.id).put("location", t.locationId)
        .put("originalId", t.originalId).put("parent", t.originalParentId).put("name", t.originalName)
        .put("path", t.originalPath).put("trashed", t.trashedAtMillis).put("size", t.sizeBytes ?: JSONObject.NULL)
        .put("modified", t.modifiedAtMillis ?: JSONObject.NULL).put("created", t.createdAtMillis ?: JSONObject.NULL)
        .put("directory", t.isDirectory).put("available", t.available)
    private fun trash(j: JSONObject) = TrashEntry(j.getString("id"), j.getString("location"),
        j.getString("originalId"), j.getString("parent"), j.getString("name"), j.getString("path"),
        j.getLong("trashed"), j.longOrNull("size"), j.longOrNull("modified"), j.longOrNull("created"),
        j.getBoolean("directory"), j.getBoolean("available"))
    private fun entryJson(e: FileEntry) = JSONObject().put("id", e.id).put("location", e.locationId)
        .put("parent", e.parentId ?: JSONObject.NULL).put("name", e.name).put("path", e.relativePath)
        .put("directory", e.isDirectory).put("mime", e.mimeType ?: JSONObject.NULL)
        .put("size", e.sizeBytes ?: JSONObject.NULL).put("modified", e.modifiedAtMillis ?: JSONObject.NULL)
        .put("created", e.createdAtMillis ?: JSONObject.NULL).put("readable", e.readable).put("writable", e.writable)
    private fun entry(j: JSONObject) = FileEntry(j.getString("id"), j.getString("location"),
        j.stringOrNull("parent"), j.getString("name"), j.getString("path"), j.getBoolean("directory"),
        j.stringOrNull("mime"), j.longOrNull("size"), j.longOrNull("modified"), j.longOrNull("created"),
        j.getBoolean("readable"), j.getBoolean("writable"))
}
