package io.github.alexanderwilhelmsenberg.modular.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/**
 * Resolves grant identity from the currently installed APK signer set.
 *
 * Grants intentionally bind to the current signer set rather than silently
 * following a signing-key rotation. A changed signer therefore requires a new
 * explicit authorization decision.
 */
class AndroidModuleIdentityResolver(
    context: Context,
) : ModuleIdentityResolver {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager
    private val corePackageName = appContext.packageName

    override fun resolve(
        packageName: String,
    ): Result<ModuleIdentity> =
        runCatching {
            require(packageName.isNotBlank()) {
                "Module package name must not be blank"
            }

            val packageInfo =
                if (
                    Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.TIRAMISU
                ) {
                    packageManager.getPackageInfo(
                        packageName,
                        PackageManager.PackageInfoFlags.of(
                            PackageManager
                                .GET_SIGNING_CERTIFICATES
                                .toLong(),
                        ),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getPackageInfo(
                        packageName,
                        PackageManager.GET_SIGNING_CERTIFICATES,
                    )
                }

            val signingInfo = requireNotNull(
                packageInfo.signingInfo,
            ) {
                "Package has no signing information: " +
                    packageName
            }

            val digests = signingInfo.apkContentsSigners
                .map { signature ->
                    sha256(signature.toByteArray())
                }
                .distinct()
                .sorted()

            ModuleIdentity(
                packageName = packageName,
                signerSha256 = digests,
            )
        }

    override fun isSameSignerAsCore(
        identity: ModuleIdentity,
    ): Boolean {
        val coreIdentity =
            resolve(corePackageName).getOrNull()
                ?: return false

        return identity.signerSha256 ==
            coreIdentity.signerSha256
    }

    private fun sha256(
        value: ByteArray,
    ): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value)
            .joinToString(separator = "") { byte ->
                "%02x".format(byte)
            }
}
