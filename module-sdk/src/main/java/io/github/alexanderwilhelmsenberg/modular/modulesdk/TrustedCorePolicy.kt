package io.github.alexanderwilhelmsenberg.modular.modulesdk

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/** Exact Core package identities and their accepted signing policy. */
class TrustedCorePolicy private constructor(
    private val entries: List<Entry>,
) {
    data class Entry internal constructor(
        val packageName: String,
        val certificateSha256: Set<String>,
        val allowSameSigner: Boolean,
    )

    /**
     * First-party default: only the official Core package IDs, signed by this
     * module's signer. An independently signed module should use [pinned] with
     * the expected Core certificate digest obtained out of band.
     */
    companion object {
        const val CORE_RELEASE_PACKAGE = "io.github.alexanderwilhelmsenberg.modular"
        const val CORE_DEBUG_PACKAGE = "io.github.alexanderwilhelmsenberg.modular.debug"

        fun officialSameSigner(): TrustedCorePolicy = TrustedCorePolicy(
            listOf(
                Entry(CORE_RELEASE_PACKAGE, emptySet(), true),
                Entry(CORE_DEBUG_PACKAGE, emptySet(), true),
            ),
        )

        /** Certificate digests are SHA-256 of DER APK signer certificates. */
        fun pinned(packageCertificates: Map<String, Set<String>>): TrustedCorePolicy {
            require(packageCertificates.isNotEmpty()) { "At least one Core identity is required" }
            return TrustedCorePolicy(packageCertificates.map { (name, digests) ->
                require(name.isNotBlank() && digests.isNotEmpty()) { "Invalid Core identity" }
                val normalized = digests.map { digest ->
                    digest.lowercase().also {
                        require(it.matches(Regex("[0-9a-f]{64}"))) { "Invalid certificate digest" }
                    }
                }.toSet()
                Entry(name, normalized, false)
            })
        }

        /**
         * Explicit local enrollment for a selected already installed Core APK.
         * The caller must choose the exact package ID and establish the initial
         * APK's authenticity before using this convenience method. The signer
         * snapshot is held only for this process and rechecked on every call.
         */
        fun pinnedToInstalled(context: Context, packageNames: Set<String>): TrustedCorePolicy {
            require(packageNames.isNotEmpty()) { "At least one Core identity is required" }
            val manager = context.packageManager
            return pinned(packageNames.associateWith { name ->
                require(name.isNotBlank()) { "Invalid Core package" }
                val info = if (Build.VERSION.SDK_INT >= 33) {
                    manager.getPackageInfo(
                        name,
                        PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    manager.getPackageInfo(name, PackageManager.GET_SIGNING_CERTIFICATES)
                }
                val signers = info.signingInfo?.apkContentsSigners?.toList().orEmpty()
                require(signers.isNotEmpty()) { "Core has no signing identity" }
                signers.map(::sha256).toSet()
            })
        }

        private fun sha256(signature: android.content.pm.Signature): String =
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    /** Resolves the installed package again for every privileged Binder call. */
    fun isTrusted(context: Context, callerUid: Int): Boolean {
        val manager = context.packageManager
        val uidPackages = manager.getPackagesForUid(callerUid)?.toSet().orEmpty()
        return entries.any { entry ->
            if (entry.packageName !in uidPackages) return@any false
            val info = try {
                if (Build.VERSION.SDK_INT >= 33) {
                    manager.getPackageInfo(
                        entry.packageName,
                        PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    manager.getPackageInfo(entry.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                }
            } catch (_: PackageManager.NameNotFoundException) {
                return@any false
            }
            if (info.applicationInfo?.uid != callerUid) return@any false
            if (entry.allowSameSigner) {
                return@any manager.checkSignatures(context.packageName, entry.packageName) ==
                    PackageManager.SIGNATURE_MATCH
            }
            val signers = info.signingInfo?.apkContentsSigners?.toList().orEmpty()
            if (signers.isEmpty()) return@any false
            val installed = signers.map(::sha256).toSet()
            installed == entry.certificateSha256
        }
    }
}
