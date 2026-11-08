package io.github.alexanderwilhelmsenberg.modular.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class AppCatalogCacheTest {
    private val profiles = listOf(AppProfile(7, false), AppProfile(21, true))
    private val apps = listOf(
        app(7, "first", "First").copy(androidCategory = 0),
        app(21, "work", "Work", enabled = false),
    )

    @Test
    fun roundTripPreservesProfileIdentityAndAppMetadata() {
        val snapshot = CachedAppCatalog("en-US", profiles, apps)
        assertEquals(snapshot, AppCatalogCacheCodec.decode(AppCatalogCacheCodec.encode(snapshot)))
        assertTrue(snapshot.isUsable("en-US", profiles))
        assertFalse(snapshot.isUsable("fr-FR", profiles))
        assertFalse(snapshot.isUsable("en-US", profiles.take(1)))
        assertFalse(snapshot.isUsable("en-US", listOf(profiles[0], profiles[1].copy(quietModeEnabled = false))))
    }

    @Test
    fun truncatedOversizedAndUnknownProfileSnapshotsAreRejected() {
        val encoded = AppCatalogCacheCodec.encode(CachedAppCatalog("en-US", profiles, apps))
        assertNull(AppCatalogCacheCodec.decode(encoded.copyOf(encoded.size - 1)))
        assertNull(AppCatalogCacheCodec.decode(ByteArray(AppCatalogCacheCodec.MAX_BYTES + 1)))
        assertNull(AppCatalogCacheCodec.decode(encoded + byteArrayOf(1)))
        val unknownProfile = CachedAppCatalog("en-US", profiles.take(1), apps)
        assertNull(AppCatalogCacheCodec.decode(AppCatalogCacheCodec.encode(unknownProfile)))
    }

    @Test
    fun previousCacheFormatKeepsAppsWithUnknownCategoryUntilRefresh() {
        val bytes = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(0x4d414331)
                data.writeInt(1)
                data.writeUTF("en-US")
                data.writeInt(1)
                data.writeLong(7)
                data.writeBoolean(false)
                data.writeInt(1)
                data.writeUTF("example.app")
                data.writeUTF("Main")
                data.writeLong(7)
                data.writeUTF("App")
                data.writeLong(123)
                data.writeBoolean(true)
                data.writeBoolean(false)
            }
        }.toByteArray()
        val decoded = AppCatalogCacheCodec.decode(bytes)
        assertEquals(1, decoded?.apps?.size)
        assertNull(decoded?.apps?.single()?.androidCategory)
    }

    @Test
    fun packageUpdateTouchesOnlyMatchingPackageAndProfile() {
        val samePackageOtherProfile = app(21, "first", "Other profile")
        val current = apps + samePackageOtherProfile
        val replacement = app(7, "first", "Renamed")
        assertEquals(
            listOf(replacement, samePackageOtherProfile, apps[1]),
            AppCatalogReconciler.replacePackage(current, 7, "first", listOf(replacement)),
        )
        assertEquals(
            listOf(samePackageOtherProfile, apps[1]),
            AppCatalogReconciler.replacePackage(current, 7, "first", emptyList()),
        )
    }

    @Test
    fun unknownProfileOrChangedLocaleRequiresFullReconcile() {
        assertTrue(AppCatalogReconciler.canUpdatePackage(profiles, profiles, 7, "en-US", "en-US"))
        assertFalse(AppCatalogReconciler.canUpdatePackage(profiles, profiles, 99, "en-US", "en-US"))
        assertFalse(AppCatalogReconciler.canUpdatePackage(profiles, profiles, 7, "en-US", "de-DE"))
        assertFalse(AppCatalogReconciler.canUpdatePackage(profiles, profiles.take(1), 7, "en-US", "en-US"))
    }

    private fun app(
        serial: Long,
        name: String,
        label: String,
        enabled: Boolean = true,
    ) = LaunchableApp(
        AppKey(name, "$name.Main", serial),
        label,
        firstInstallTime = 1234,
        enabled = enabled,
        loadingProgress = 0.5f,
    )
}
