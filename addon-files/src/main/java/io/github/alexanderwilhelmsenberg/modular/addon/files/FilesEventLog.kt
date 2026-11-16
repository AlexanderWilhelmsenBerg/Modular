package io.github.alexanderwilhelmsenberg.modular.addon.files

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/** Bounded, device-local diagnostics. Never records file contents or action/handoff tokens. */
internal class FilesEventLog(
    read: () -> String?,
    private val write: (String) -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val entries = runCatching { JSONArray(read() ?: "[]") }.getOrDefault(JSONArray())
        .let { data -> (0 until data.length()).mapNotNull { data.optJSONObject(it) }.toMutableList() }
    @Volatile var storageError: String? = null
        private set

    @Synchronized fun record(event: String, message: String, level: String = "INFO",
        operationId: String? = null, error: Throwable? = null, details: String? = null) {
        entries.add(JSONObject().put("id", UUID.randomUUID().toString()).put("at", now())
            .put("event", event.take(80)).put("level", level).put("message", message.take(600))
            .put("operationId", operationId)
            .put("details", (error?.stackTraceToString() ?: details)?.take(4096)))
        while (entries.size > 200 || (entries.size > 1 && encoded().toByteArray().size > 192_000))
            entries.removeAt(0)
        save()
    }

    @Synchronized fun snapshot(): List<JSONObject> = entries.asReversed().map { JSONObject(it.toString()) }
    @Synchronized fun clear() { entries.clear(); save() }
    @Synchronized fun export(): String = snapshot().joinToString("\n\n") { entry ->
        "${Instant.ofEpochMilli(entry.optLong("at"))} ${entry.optString("level")} ${entry.optString("event")} " +
            entry.optString("operationId", "") + "\n" + entry.optString("message") +
            entry.optString("details", "").takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
    }.take(32_000)

    private fun encoded() = JSONArray(entries).toString()
    private fun save() {
        storageError = if (runCatching { write(encoded()) }.getOrDefault(false)) null
            else "Could not save the event log. These events remain available until Files restarts."
    }
}
