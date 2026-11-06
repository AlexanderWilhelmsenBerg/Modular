package io.github.alexanderwilhelmsenberg.modular.modules

import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import io.github.alexanderwilhelmsenberg.modular.moduleapi.ModuleContract
import io.github.alexanderwilhelmsenberg.modular.moduleapi.ModuleDescriptor
import io.github.alexanderwilhelmsenberg.modular.moduleapi.ModuleDescriptorCodec
import java.security.MessageDigest
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Reads the descriptor packaged with the discovered service. It conveys display data only. */
internal object ModuleDescriptorDiscovery {
    fun inspect(packageManager: PackageManager, service: ServiceInfo): Discovery {
        val metadata = service.metaData
        if (metadata?.containsKey(ModuleContract.MODULE_DESCRIPTOR_METADATA_KEY) != true) {
            return Discovery(null, null, null)
        }
        return try {
            val resourceId = requireNotNull(metadata).getInt(ModuleContract.MODULE_DESCRIPTOR_METADATA_KEY, 0)
            require(resourceId != 0) { "Metadata must reference a raw JSON resource" }
            val resources = packageManager.getResourcesForApplication(service.packageName)
            val raw = resources.openRawResource(resourceId).use { input ->
                val bytes = ByteArray(MAX_DESCRIPTOR_BYTES + 1)
                var count = 0
                while (count < bytes.size) {
                    val read = input.read(bytes, count, bytes.size - count)
                    if (read < 0) break
                    count += read
                }
                require(count <= MAX_DESCRIPTOR_BYTES) { "Descriptor exceeds 32 KiB" }
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, 0, count))
                    .toString()
            }
            val descriptor = ModuleDescriptorCodec.parse(raw)
            descriptor.managementActivity?.let { activity ->
                validateManagementActivity(packageManager, service, activity)
            }
            Discovery(descriptor, null, sha256(raw))
        } catch (error: Exception) {
            val reason = if (error is IllegalArgumentException) error.message ?: "Invalid JSON"
                else "Resource could not be read as UTF-8 JSON"
            Discovery(null, "Invalid packaged module descriptor: $reason. Check the service's raw JSON metadata.", null)
        }
    }

    /** A declared settings screen must be launchable from Core in this provider's package. */
    fun validateManagementActivity(
        packageManager: PackageManager,
        service: ServiceInfo,
        declaration: String,
    ) {
        val packageName = service.packageName
        val className = if (declaration.startsWith('.')) {
            val packageInfo = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.getPackageInfo(packageName,
                        PackageManager.PackageInfoFlags.of(PackageManager.GET_ACTIVITIES.toLong()))
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
                }
            } catch (_: PackageManager.NameNotFoundException) {
                throw IllegalArgumentException("Management Activity provider package was not found")
            }
            val matches = packageInfo.activities.orEmpty().filter {
                it.packageName == packageName && it.name.endsWith(declaration)
            }
            require(matches.size == 1) {
                "Management Activity must resolve to exactly one Activity in the provider package"
            }
            matches.single().name
        } else declaration
        val component = ComponentName(packageName, className)
        val activity = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getActivityInfo(component, PackageManager.ComponentInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getActivityInfo(component, 0)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            throw IllegalArgumentException("Management Activity was not found in the provider package")
        }
        require(activity.packageName == packageName && activity.name == className) {
            "Management Activity does not resolve to the declared provider component"
        }
        require(activity.exported && activity.enabled && activity.applicationInfo.enabled) {
            "Management Activity must be exported and enabled"
        }
    }

    private fun sha256(raw: String): String = MessageDigest.getInstance("SHA-256")
        .digest(raw.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    data class Discovery(
        val descriptor: ModuleDescriptor?,
        val error: String?,
        val fingerprint: String?,
    )

    private const val MAX_DESCRIPTOR_BYTES = 32 * 1024
}
