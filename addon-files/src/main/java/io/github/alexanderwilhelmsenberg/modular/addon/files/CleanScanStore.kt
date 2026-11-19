package io.github.alexanderwilhelmsenberg.modular.addon.files

import android.util.AtomicFile
import io.github.alexanderwilhelmsenberg.modular.addon.files.cleaner.CleanerEngine
import io.github.alexanderwilhelmsenberg.modular.addon.files.cleaner.CleanerRepositoryAdapter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID

/** Persists read-only requests/results, never a deletion plan or destructive authorization. */
internal class CleanScanStore(file: File) {
    data class Saved(val id: String, val name: String, val options: CleanerEngine.Options,
        val attempts: Int = 0, val snapshot: CleanerRepositoryAdapter.Snapshot? = null,
        val files: Int = 0, val folders: Int = 0, val analysis: CleanerEngine.Analysis? = null)
    private val storage = AtomicFile(file)
    private var loaded = false
    private var cached: Saved? = null
    private var lastProgress = 0L
    @Synchronized fun load(): Saved? {
        if (loaded) return cached
        loaded = true
        cached = runCatching {
            val j = JSONObject(String(storage.readFully()))
            require(j.getInt("version") == 1)
            val o = j.getJSONObject("options")
            val options = CleanerEngine.Options(o.getString("scope"), o.getBoolean("subfolders"),
                o.getJSONArray("excluded").strings().toSet(), o.getJSONArray("protected").strings().toSet(),
                o.getLong("large"), o.getLong("oldDays"), Instant.ofEpochMilli(o.getLong("at")),
                o.getJSONArray("categories").strings().map { CleanerEngine.Category.valueOf(it) }.toSet())
            val snapshot = j.optJSONObject("snapshot")?.let { s -> CleanerRepositoryAdapter.Snapshot(
                s.getJSONArray("entries").objects().map(::entry), s.getJSONArray("errors").strings()) }
            val analysis = j.optJSONObject("analysis")?.let { a -> CleanerEngine.Analysis(
                a.getJSONArray("entries").objects().map(::entry).associateBy { it.id() },
                a.getJSONArray("groups").objects().map { g -> CleanerEngine.Group(g.getString("id"),
                    CleanerEngine.Category.valueOf(g.getString("category")), g.getJSONArray("members").strings(),
                    g.nullable("keeper"), g.getBoolean("choice"), g.nullable("base"), g.nullable("evidence")) },
                a.getJSONArray("suggestions").objects().map { s -> CleanerEngine.Suggestion(s.getString("entry"),
                    CleanerEngine.Category.valueOf(s.getString("category")), s.getString("reason"), s.getBoolean("selected")) },
                a.getJSONArray("warnings").strings(), options) }
            Saved(j.getString("id"), j.getString("name"), options, j.optInt("attempts"), snapshot,
                j.optInt("files"), j.optInt("folders"), analysis)
        }.getOrNull()
        return cached
    }
    @Synchronized fun begin(name: String, options: CleanerEngine.Options): Saved =
        Saved(UUID.randomUUID().toString(), name, options).also(::write)
    @Synchronized fun recovering(saved: Saved): Saved {
        require(load()?.id == saved.id) { "Saved scan is no longer current" }
        return saved.copy(attempts = saved.attempts + 1).also(::write)
    }
    @Synchronized fun checkpoint(id: String, snapshot: CleanerRepositoryAdapter.Snapshot) {
        load()?.takeIf { it.id == id }?.let { write(it.copy(snapshot = snapshot)) }
    }
    @Synchronized fun complete(id: String, analysis: CleanerEngine.Analysis) {
        load()?.takeIf { it.id == id }?.let { write(it.copy(analysis = analysis, snapshot = null, attempts = 0)) }
    }
    @Synchronized fun clear(id: String? = null) {
        if (id != null && load()?.id != id) return
        storage.delete(); cached = null; loaded = true
    }
    @Synchronized fun progress(id: String, files: Int, folders: Int) {
        val current = load()?.takeIf { it.id == id } ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastProgress < 2_000) return
        lastProgress = now
        write(current.copy(files = files, folders = folders))
    }
    private fun write(s: Saved) {
        val o = s.options
        val json = JSONObject().put("version", 1).put("id", s.id).put("name", s.name).put("attempts", s.attempts)
            .put("files", s.files).put("folders", s.folders).put("options", JSONObject()
                .put("scope", o.scopeId()).put("subfolders", o.includeSubfolders())
                .put("excluded", JSONArray(o.excludedIds())).put("protected", JSONArray(o.protectedIds()))
                .put("large", o.largeBytes()).put("oldDays", o.oldDownloadDays()).put("at", o.scannedAt().toEpochMilli())
                .put("categories", JSONArray(o.selectedCategories().map { it.name })))
        s.snapshot?.let { snapshot -> json.put("snapshot", JSONObject()
            .put("entries", JSONArray(snapshot.entries.map(::entryJson))).put("errors", JSONArray(snapshot.errors))) }
        s.analysis?.let { a -> json.put("analysis", JSONObject()
            .put("entries", JSONArray(a.entries().values.map(::entryJson)))
            .put("groups", JSONArray(a.groups().map { g -> JSONObject().put("id", g.id()).put("category", g.category().name)
                .put("members", JSONArray(g.memberIds())).put("keeper", g.suggestedKeeperId() ?: JSONObject.NULL)
                .put("choice", g.requiresKeeperChoice()).put("base", g.baseName() ?: JSONObject.NULL)
                .put("evidence", g.evidence() ?: JSONObject.NULL) }))
            .put("suggestions", JSONArray(a.suggestions().map { f -> JSONObject().put("entry", f.entryId())
                .put("category", f.category().name).put("reason", f.reason()).put("selected", f.selectedByDefault()) }))
            .put("warnings", JSONArray(a.coverageWarnings()))) }
        storage.baseFile.parentFile?.mkdirs()
        val stream = storage.startWrite()
        try { stream.write(json.toString().toByteArray()); storage.finishWrite(stream); cached = s; loaded = true }
        catch (error: Exception) { storage.failWrite(stream); throw error }
    }
    private fun JSONArray.strings() = (0 until length()).map { getString(it) }
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    private fun JSONObject.nullable(key: String): String? = if (isNull(key)) null else getString(key)
    private fun JSONObject.number(key: String): Long? = if (isNull(key)) null else getLong(key)
    private fun entryJson(e: CleanerEngine.Entry): JSONObject = JSONObject().put("id", e.id()).put("volume", e.volumeId())
        .put("parent", e.parentId() ?: JSONObject.NULL).put("path", e.path()).put("name", e.name()).put("kind", e.kind().name)
        .put("size", e.size() ?: JSONObject.NULL).put("modified", e.modified()?.toEpochMilli() ?: JSONObject.NULL)
        .put("created", e.created()?.toEpochMilli() ?: JSONObject.NULL).put("revision", e.revision())
        .put("complete", e.childrenComplete()).apply { e.apk()?.let { a -> put("apk", JSONObject()
            .put("package", a.packageName() ?: JSONObject.NULL).put("signer", a.signer() ?: JSONObject.NULL)
            .put("code", a.versionCode() ?: JSONObject.NULL).put("version", a.versionName() ?: JSONObject.NULL)
            .put("architecture", a.architecture() ?: JSONObject.NULL).put("channel", a.channel() ?: JSONObject.NULL)
            .put("split", a.splitSet() ?: JSONObject.NULL).put("debug", a.debugBuild())) } }
    private fun entry(j: JSONObject): CleanerEngine.Entry = CleanerEngine.Entry(j.getString("id"), j.getString("volume"),
        j.nullable("parent"), j.getString("path"), j.getString("name"), CleanerEngine.Kind.valueOf(j.getString("kind")),
        j.number("size"), j.number("modified")?.let(Instant::ofEpochMilli), j.number("created")?.let(Instant::ofEpochMilli),
        j.getString("revision"), j.getBoolean("complete"), j.optJSONObject("apk")?.let { a -> CleanerEngine.ApkEvidence(
            a.nullable("package"), a.nullable("signer"), a.number("code"), a.nullable("version"),
            a.nullable("architecture"), a.nullable("channel"), a.nullable("split"), a.getBoolean("debug")) })
}
