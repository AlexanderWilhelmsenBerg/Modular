package io.github.alexanderwilhelmsenberg.modular.organization

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Immutable sensitive records. Only opaque file IDs belong in the backed-up organization document. */
class SavedConditionStore(context: Context) {
    private val directory = File(context.noBackupFilesDir, "folder_conditions_v1")
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
    private val idPattern = Regex("[a-f0-9-]{36}")

    @Serializable private data class Record(val version: Int = 1, val condition: FolderCondition)

    /** Stage before the organization transaction. A failed transaction leaves these invisible. */
    @Synchronized fun stage(conditions: List<FolderCondition>): List<String> {
        require(conditions.size <= 16) { "Too many folder rules" }
        require(conditions.map { it.id }.distinct().size == conditions.size) { "Duplicate rule IDs" }
        directory.mkdirs()
        require(directory.isDirectory) { "Cannot create private rule directory" }
        return conditions.map { condition ->
            validate(condition)
            val id = UUID.randomUUID().toString()
            val target = fileFor(id)
            val bytes = json.encodeToString(Record(condition = condition)).toByteArray(Charsets.UTF_8)
            require(bytes.size <= 32_768) { "Rule is too large" }
            val temp = File(directory, "$id.tmp")
            FileOutputStream(temp).use { output -> output.write(bytes); output.fd.sync() }
            if (!temp.renameTo(target)) { temp.delete(); error("Cannot save private rule") }
            id
        }
    }

    @Synchronized fun load(id: String): FolderCondition? {
        if (!idPattern.matches(id)) return null
        val file = fileFor(id)
        if (!file.isFile || file.length() > 32_768) return null
        return runCatching {
            val record = json.decodeFromString<Record>(file.readText(Charsets.UTF_8))
            record.takeIf { it.version == 1 }?.condition?.also(::validate)
        }.getOrNull()
    }

    fun loadAll(ids: List<String>): Map<String, FolderCondition> = ids.mapNotNull { id ->
        load(id)?.let { id to it }
    }.toMap()

    /** Call after a successful commit and on startup using every committed folder reference. */
    @Synchronized fun reconcile(referencedIds: Set<String>) {
        if (!directory.isDirectory) return
        directory.listFiles()?.forEach { file ->
            if (file.isFile && (file.name.endsWith(".tmp") ||
                file.name.removeSuffix(".json") !in referencedIds)) file.delete()
        }
    }

    private fun fileFor(id: String) = File(directory, "$id.json")

    private fun validate(condition: FolderCondition) {
        require(condition.id.length in 1..80) { "Invalid rule ID" }
        when (condition) {
            is FolderCondition.Headset -> Unit
            is FolderCondition.Schedule -> {
                require(condition.intervals.size <= 16) { "Too many intervals" }
                require(condition.intervals.all { it.weekdays.all { day -> day in 1..7 } &&
                    it.startMinute in 0..1439 && it.endMinute in 0..1439 }) { "Invalid schedule" }
            }
            is FolderCondition.Wifi -> {
                require(condition.ssids.size in 0..16 && condition.ssids.all { it.isNotBlank() && it.length <= 64 })
                    { "Invalid Wi-Fi name" }
            }
            is FolderCondition.Area -> {
                require(condition.areas.size <= 16) { "Too many areas" }
                require(condition.areas.all { it.name.length in 1..80 &&
                    it.latitude.isFinite() && it.latitude in -90.0..90.0 &&
                    it.longitude.isFinite() && it.longitude in -180.0..180.0 &&
                    it.radiusMeters.isFinite() && it.radiusMeters in 25.0..100_000.0 })
                    { "Invalid saved area" }
            }
        }
    }
}
