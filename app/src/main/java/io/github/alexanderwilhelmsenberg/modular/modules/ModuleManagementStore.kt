package io.github.alexanderwilhelmsenberg.modular.modules

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class ModuleManagementEvent(
    val timestampMillis: Long,
    val component: String,
    val stage: String,
    val message: String,
    val isError: Boolean,
)

/** Small durable store for user enablement choices and the module lifecycle event history. */
class ModuleManagementStore(
    context: Context,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val preferences: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val lock = Any()
    private val _events = MutableStateFlow(loadEvents())
    val events: StateFlow<List<ModuleManagementEvent>> = _events.asStateFlow()

    fun isEnabled(component: ComponentName): Boolean = synchronized(lock) {
        !disabledComponents().contains(component.flattenToString())
    }

    fun setEnabled(component: ComponentName, enabled: Boolean) = synchronized(lock) {
        val disabled = disabledComponents()
        val key = component.flattenToString()
        if (enabled) disabled.remove(key) else disabled.add(key)
        saveDisabledComponents(disabled)
    }

    fun knownProviderId(component: ComponentName): String? = synchronized(lock) {
        try {
            preferences.getString(PROVIDER_PREFIX + component.flattenToString(), null)
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Could not read cached module identity", exception)
            null
        }
    }

    fun rememberProviderId(component: ComponentName, providerId: String) = synchronized(lock) {
        try {
            preferences.edit()
                .putString(PROVIDER_PREFIX + component.flattenToString(), providerId)
                .apply()
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Could not cache module identity", exception)
        }
    }

    fun record(
        component: String,
        stage: String,
        message: String,
        isError: Boolean = false,
    ) = synchronized(lock) {
        val updated = (_events.value + ModuleManagementEvent(
            timestampMillis = nowMillis(),
            component = component.take(MAX_COMPONENT_LENGTH),
            stage = stage.take(MAX_STAGE_LENGTH),
            message = message.take(MAX_MESSAGE_LENGTH),
            isError = isError,
        )).takeLast(MAX_EVENTS)
        _events.value = updated
        try {
            preferences.edit().putString(EVENTS_KEY, encodeEvents(updated)).apply()
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Could not persist module lifecycle history", exception)
        }
    }

    fun clearEvents() = synchronized(lock) {
        _events.value = emptyList()
        try {
            preferences.edit().remove(EVENTS_KEY).apply()
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Could not clear module lifecycle history", exception)
        }
    }

    private fun disabledComponents(): MutableSet<String> = try {
        preferences.getStringSet(DISABLED_KEY, emptySet())
            ?.toMutableSet() ?: mutableSetOf()
    } catch (exception: RuntimeException) {
        Log.w(TAG, "Could not read module enablement choices", exception)
        mutableSetOf()
    }

    private fun saveDisabledComponents(disabled: Set<String>) {
        try {
            preferences.edit().putStringSet(DISABLED_KEY, disabled.toSet()).apply()
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Could not persist module enablement choices", exception)
        }
    }

    private fun loadEvents(): List<ModuleManagementEvent> {
        val raw = try {
            preferences.getString(EVENTS_KEY, null)
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Could not read module lifecycle history", exception)
            return listOf(recoveryEvent("Could not read lifecycle history"))
        } ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                ModuleManagementEvent(
                    timestampMillis = item.getLong("timestampMillis"),
                    component = item.getString("component"),
                    stage = item.getString("stage"),
                    message = item.getString("message"),
                    isError = item.getBoolean("isError"),
                )
            }.takeLast(MAX_EVENTS)
        } catch (exception: Exception) {
            Log.w(TAG, "Discarding unreadable module lifecycle history", exception)
            try {
                preferences.edit().remove(EVENTS_KEY).apply()
            } catch (_: RuntimeException) {
                // The in-memory store remains usable if preferences are unavailable.
            }
            listOf(recoveryEvent("Unreadable lifecycle history was cleared"))
        }
    }

    private fun recoveryEvent(message: String) = ModuleManagementEvent(
        timestampMillis = nowMillis(),
        component = "system",
        stage = "storage-recovery",
        message = message,
        isError = true,
    )

    private fun encodeEvents(events: List<ModuleManagementEvent>): String = JSONArray().apply {
        events.forEach { event ->
            put(JSONObject().apply {
                put("timestampMillis", event.timestampMillis)
                put("component", event.component)
                put("stage", event.stage)
                put("message", event.message)
                put("isError", event.isError)
            })
        }
    }.toString()

    private companion object {
        const val TAG = "ModuleManagement"
        const val PREFERENCES_NAME = "module_management"
        const val DISABLED_KEY = "disabled_components"
        const val EVENTS_KEY = "lifecycle_events"
        const val PROVIDER_PREFIX = "provider_id:"
        const val MAX_EVENTS = 200
        const val MAX_COMPONENT_LENGTH = 512
        const val MAX_STAGE_LENGTH = 64
        const val MAX_MESSAGE_LENGTH = 2_048
    }
}
