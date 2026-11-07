package io.github.alexanderwilhelmsenberg.modular.apps

import android.content.Context
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/** Private, disposable snapshot. It is display data until LauncherApps has reconciled it. */
internal data class CachedAppCatalog(
    val localeTags: String,
    val profiles: List<AppProfile>,
    val apps: List<LaunchableApp>,
) {
    fun isUsable(locale: String, currentProfiles: List<AppProfile>): Boolean =
        localeTags == locale && profiles == currentProfiles
}

internal object AppCatalogCacheCodec {
    private const val MAGIC = 0x4d414331 // MAC1
    private const val VERSION = 1
    const val MAX_BYTES = 4 * 1024 * 1024
    private const val MAX_PROFILES = 32
    private const val MAX_APPS = 20_000

    fun encode(snapshot: CachedAppCatalog): ByteArray {
        require(snapshot.profiles.size <= MAX_PROFILES && snapshot.apps.size <= MAX_APPS)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeUTF(snapshot.localeTags)
            out.writeInt(snapshot.profiles.size)
            snapshot.profiles.forEach { profile ->
                out.writeLong(profile.serialNumber)
                out.writeBoolean(profile.quietModeEnabled)
            }
            out.writeInt(snapshot.apps.size)
            snapshot.apps.forEach { app ->
                out.writeUTF(app.key.packageName)
                out.writeUTF(app.key.activityName)
                out.writeLong(app.key.profileSerial)
                out.writeUTF(app.label)
                out.writeLong(app.firstInstallTime)
                out.writeBoolean(app.enabled)
                out.writeBoolean(app.loadingProgress != null)
                app.loadingProgress?.let { out.writeFloat(it) }
            }
        }
        return bytes.toByteArray().also { require(it.size <= MAX_BYTES) }
    }

    fun decode(bytes: ByteArray): CachedAppCatalog? {
        if (bytes.size > MAX_BYTES) return null
        return try {
            val input = ByteArrayInputStream(bytes)
            val data = DataInputStream(input)
            if (data.readInt() != MAGIC || data.readInt() != VERSION) return null
            val locale = data.readUTF()
            val profileCount = data.readInt()
            if (profileCount !in 0..MAX_PROFILES) return null
            val profiles = List(profileCount) {
                AppProfile(data.readLong(), data.readBoolean())
            }
            if (profiles.any { it.serialNumber < 0 } ||
                profiles.map { it.serialNumber }.toSet().size != profiles.size
            ) return null
            val appCount = data.readInt()
            if (appCount !in 0..MAX_APPS) return null
            val serials = profiles.map { it.serialNumber }.toSet()
            val apps = List(appCount) {
                LaunchableApp(
                    AppKey(data.readUTF(), data.readUTF(), data.readLong()),
                    data.readUTF(),
                    data.readLong(),
                    data.readBoolean(),
                    if (data.readBoolean()) data.readFloat() else null,
                )
            }
            if (apps.any { app ->
                    app.key.profileSerial !in serials ||
                        app.key.packageName.isBlank() || app.key.activityName.isBlank()
                } || apps.map { it.key }.toSet().size != apps.size || input.available() != 0
            ) return null
            CachedAppCatalog(locale, profiles, apps)
        } catch (_: Exception) {
            null
        }
    }
}

internal class AppCatalogCache(context: Context) {
    private val file = AtomicFile(context.noBackupFilesDir.resolve("app-catalog-v1.bin"))

    fun read(): CachedAppCatalog? = synchronized(ioLock) { readLocked() }

    private fun readLocked(): CachedAppCatalog? {
        return try {
            if (file.baseFile.length() > AppCatalogCacheCodec.MAX_BYTES) return null
            val bytes = file.openRead().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > AppCatalogCacheCodec.MAX_BYTES) return null
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            AppCatalogCacheCodec.decode(bytes)
        } catch (_: IOException) {
            null
        }
    }

    fun write(snapshot: CachedAppCatalog) = synchronized(ioLock) { writeLocked(snapshot) }

    private fun writeLocked(snapshot: CachedAppCatalog) {
        val bytes = try {
            AppCatalogCacheCodec.encode(snapshot)
        } catch (_: Exception) {
            return
        }
        var stream: java.io.FileOutputStream? = null
        try {
            stream = file.startWrite()
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (_: IOException) {
            stream?.let(file::failWrite)
        }
    }

    private companion object {
        // Android can keep an old Activity's repository alive during recreation.
        // AtomicFile coordinates a single writer but does not serialize instances.
        private val ioLock = Any()
    }
}
