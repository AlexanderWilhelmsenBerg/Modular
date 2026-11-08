package io.github.alexanderwilhelmsenberg.modular.persistence

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** DataStore's single active owner for each preferences file in this app process. */
internal object ProcessPreferenceDataStores {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stores = mutableMapOf<String, DataStore<Preferences>>()

    fun get(context: Context, fileName: String): DataStore<Preferences> {
        val file = context.applicationContext.preferencesDataStoreFile(fileName).canonicalFile
        return synchronized(stores) {
            stores.getOrPut(file.path) {
                PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
            }
        }
    }
}
