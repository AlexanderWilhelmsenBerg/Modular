package io.github.alexanderwilhelmsenberg.modular.security

import io.github.alexanderwilhelmsenberg.modular.moduleapi.CapabilityId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal object ModuleGrantStateCodec {
    private const val CURRENT_SCHEMA_VERSION = 1

    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    fun encode(state: ModuleGrantState): String =
        json.encodeToString(
            PersistedModuleGrantState.serializer(),
            state.toPersisted(),
        )

    fun decode(raw: String?): ModuleGrantState {
        if (raw.isNullOrBlank()) {
            return ModuleGrantState()
        }

        val persisted = json.decodeFromString(
            PersistedModuleGrantState.serializer(),
            raw,
        )

        require(
            persisted.schemaVersion in 1..CURRENT_SCHEMA_VERSION,
        ) {
            "Unsupported module-grant schema version " +
                persisted.schemaVersion
        }

        return persisted.toDomain()
    }

    private fun ModuleGrantState.toPersisted() =
        PersistedModuleGrantState(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            records = records
                .sortedBy { record ->
                    record.identity.key
                }
                .map { record ->
                    PersistedModuleGrantRecord(
                        packageName =
                            record.identity.packageName,
                        signerSha256 =
                            record.identity.signerSha256,
                        grantedCapabilities =
                            record.grantedCapabilities
                                .map { it.wireValue }
                                .sorted(),
                    )
                },
        )

    private fun PersistedModuleGrantState.toDomain():
        ModuleGrantState {
        val domainRecords = records.map { record ->
            val identity = ModuleIdentity(
                packageName = record.packageName,
                signerSha256 =
                    record.signerSha256
                        .distinct()
                        .sorted(),
            )

            ModuleGrantRecord(
                identity = identity,
                grantedCapabilities =
                    record.grantedCapabilities
                        .map { wireValue ->
                            requireNotNull(
                                CapabilityId.parse(
                                    wireValue,
                                ),
                            ) {
                                "Invalid persisted grant " +
                                    wireValue
                            }
                        }
                        .toSet(),
            )
        }

        val duplicateIdentities = domainRecords
            .groupBy { record ->
                record.identity.key
            }
            .filterValues { duplicates ->
                duplicates.size > 1
            }
            .keys

        require(duplicateIdentities.isEmpty()) {
            "Duplicate persisted module-grant identities: " +
                duplicateIdentities.joinToString()
        }

        return ModuleGrantState(
            records = domainRecords
                .sortedBy { record ->
                    record.identity.key
                },
        )
    }

    @Serializable
    private data class PersistedModuleGrantState(
        val schemaVersion: Int,
        val records: List<PersistedModuleGrantRecord> =
            emptyList(),
    )

    @Serializable
    private data class PersistedModuleGrantRecord(
        val packageName: String,
        val signerSha256: List<String>,
        val grantedCapabilities: List<String> =
            emptyList(),
    )
}
