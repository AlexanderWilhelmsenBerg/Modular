package io.github.alexanderwilhelmsenberg.modular.security

import io.github.alexanderwilhelmsenberg.modular.moduleapi.CapabilityId

data class ModuleIdentity(
    val packageName: String,
    val signerSha256: List<String>,
) {
    init {
        require(packageName.isNotBlank()) {
            "Module package name must not be blank"
        }
        require(signerSha256.isNotEmpty()) {
            "Module identity requires at least one signer"
        }
        require(signerSha256 == signerSha256.distinct().sorted()) {
            "Module signer digests must be distinct and sorted"
        }
        require(
            signerSha256.all { digest ->
                SHA256_PATTERN.matches(digest)
            },
        ) {
            "Module signer digest must be lowercase SHA-256 hex"
        }
    }

    val key: String
        get() =
            packageName + "|" + signerSha256.joinToString(",")

    companion object {
        private val SHA256_PATTERN =
            Regex("^[0-9a-f]{64}$")
    }
}

data class ModuleGrantRecord(
    val identity: ModuleIdentity,
    val grantedCapabilities: Set<CapabilityId> = emptySet(),
)

data class ModuleGrantState(
    val records: List<ModuleGrantRecord> = emptyList(),
) {
    fun recordFor(
        identity: ModuleIdentity,
    ): ModuleGrantRecord? =
        records.firstOrNull {
            it.identity == identity
        }
}

data class ModuleAuthorizationContext(
    val identity: ModuleIdentity,
    val requestedCapabilities: Set<CapabilityId>,
)

data class ModuleSecurityStatus(
    val packageName: String,
    val identity: ModuleIdentity?,
    val sameSignerAsCore: Boolean,
    val requestedCapabilities: Set<CapabilityId>,
    val explicitGrants: Set<CapabilityId>,
    val effectiveGrants: Set<CapabilityId>,
    val unsupportedRequests: Set<CapabilityId>,
    val error: String? = null,
)
