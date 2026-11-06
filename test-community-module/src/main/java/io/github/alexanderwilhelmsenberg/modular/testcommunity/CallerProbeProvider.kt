package io.github.alexanderwilhelmsenberg.modular.testcommunity

import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import io.github.alexanderwilhelmsenberg.modular.moduleapi.IModularModuleService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Calls the module service from this APK's own UID to verify caller rejection. */
class CallerProbeProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        require(method == "probeUntrustedCaller")
        val app = requireNotNull(context)
        val result = AtomicReference<Boolean>()
        val latch = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                val token = Binder.clearCallingIdentity()
                try {
                    result.set(runCatching {
                        IModularModuleService.Stub.asInterface(service).closeSession("untrusted-probe")
                    }.exceptionOrNull() is SecurityException)
                } finally {
                    Binder.restoreCallingIdentity(token)
                    latch.countDown()
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                latch.countDown()
            }
        }
        val bound = app.bindService(
            Intent(app, CommunitySortService::class.java), connection, Context.BIND_AUTO_CREATE,
        )
        if (!bound) return Bundle().apply { putBoolean("rejected", false) }
        return try {
            Bundle().apply { putBoolean("rejected", latch.await(5, TimeUnit.SECONDS) && result.get() == true) }
        } finally {
            app.unbindService(connection)
        }
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}
