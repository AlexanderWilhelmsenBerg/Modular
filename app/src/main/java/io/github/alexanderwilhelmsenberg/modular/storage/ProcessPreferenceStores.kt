package io.github.alexanderwilhelmsenberg.modular.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** One file owner per process. Activity-scoped observers may come and go. */
internal object ProcessPreferenceStores {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stores = mutableMapOf<String, DataStore<Preferences>>()

    fun get(context: Context, name: String): DataStore<Preferences> {
        val file = context.applicationContext.preferencesDataStoreFile(name)
        return synchronized(stores) {
            stores.getOrPut(file.canonicalPath) {
                PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
            }
        }
    }
}
