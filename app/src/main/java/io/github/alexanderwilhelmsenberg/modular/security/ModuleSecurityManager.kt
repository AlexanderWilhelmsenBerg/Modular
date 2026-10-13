package io.github.alexanderwilhelmsenberg.modular.security

import io.github.alexanderwilhelmsenberg.modular.moduleapi.CapabilityId
import io.github.alexanderwilhelmsenberg.modular.moduleapi.CoreCapabilities

class ModuleSecurityManager(
    private val identityResolver: ModuleIdentityResolver,
    private val grantStore: ModuleGrantStateStore,
    private val officialBootstrapCapabilities:
        Set<CapabilityId> =
        CoreCapabilities.all.toSet(),
) {
    private val supportedCapabilities =
        CoreCapabilities.all.toSet()

    /**
     * Resolves the exact installed identity and ensures it has one persisted
     * grant record. Official same-signer modules are initialized only with the
     * Core capabilities they explicitly requested and that the bootstrap policy
     * allows. An empty record is still persisted so later revocation is sticky.
     */
    suspend fun prepareSession(
        packageName: String,
        requestedCapabilities: Set<CapabilityId>,
    ): ModuleAuthorizationContext {
        val identity =
            identityResolver.resolve(packageName).getOrThrow()

        grantStore.update { state ->
            if (state.recordFor(identity) != null) {
                state
            } else {
                val bootstrap =
                    if (
                        identityResolver
                            .isSameSignerAsCore(identity)
                    ) {
                        requestedCapabilities
                            .intersect(
                                officialBootstrapCapabilities,
                            )
                            .intersect(
                                supportedCapabilities,
                            )
                    } else {
                        emptySet()
                    }

                state.copy(
                    records = state.records +
                        ModuleGrantRecord(
                            identity = identity,
                            grantedCapabilities =
                                bootstrap,
                        ),
                )
            }
        }

        return ModuleAuthorizationContext(
            identity = identity,
            requestedCapabilities =
                requestedCapabilities.toSet(),
        )
    }

    fun effectiveCapabilities(
        authorization: ModuleAuthorizationContext,
    ): Set<CapabilityId> {
        if (grantStore.loadError.value != null) {
            return emptySet()
        }

        val currentIdentity =
            identityResolver
                .resolve(
                    authorization.identity.packageName,
                )
                .getOrNull()

        if (currentIdentity != authorization.identity) {
            return emptySet()
        }

        val explicit = grantStore.state.value
            .recordFor(authorization.identity)
            ?.grantedCapabilities
            .orEmpty()

        return explicit
            .intersect(
                authorization.requestedCapabilities,
            )
            .intersect(supportedCapabilities)
    }

    fun isGranted(
        authorization: ModuleAuthorizationContext,
        capability: CapabilityId,
    ): Boolean =
        capability in
            effectiveCapabilities(authorization)

    suspend fun grant(
        authorization: ModuleAuthorizationContext,
        capability: CapabilityId,
    ): Result<Unit> =
        runCatching {
            require(capability in supportedCapabilities) {
                "Core does not support " +
                    capability.wireValue
            }
            require(
                capability in
                    authorization.requestedCapabilities,
            ) {
                "Module did not request " +
                    capability.wireValue
            }

            val currentIdentity =
                identityResolver.resolve(
                    authorization.identity.packageName,
                ).getOrThrow()

            require(
                currentIdentity ==
                    authorization.identity,
            ) {
                "Module signing identity changed before grant"
            }

            grantStore.update { state ->
                val existing =
                    state.recordFor(
                        authorization.identity,
                    ) ?: ModuleGrantRecord(
                        authorization.identity,
                    )

                val replacement = existing.copy(
                    grantedCapabilities =
                        existing.grantedCapabilities +
                            capability,
                )

                state.copy(
                    records =
                        state.records.filterNot {
                            it.identity ==
                                authorization.identity
                        } + replacement,
                )
            }

            Unit
        }

    suspend fun revoke(
        packageName: String,
        capability: CapabilityId,
    ): Result<Unit> =
        runCatching {
            val identity =
                identityResolver.resolve(
                    packageName,
                ).getOrThrow()

            grantStore.update { state ->
                val existing =
                    state.recordFor(identity)
                        ?: ModuleGrantRecord(identity)

                val replacement = existing.copy(
                    grantedCapabilities =
                        existing.grantedCapabilities -
                            capability,
                )

                state.copy(
                    records =
                        state.records.filterNot {
                            it.identity == identity
                        } + replacement,
                )
            }

            Unit
        }

    fun status(
        packageName: String,
        requestedCapabilities: Set<CapabilityId>,
    ): ModuleSecurityStatus {
        val identityResult =
            identityResolver.resolve(packageName)

        return identityResult.fold(
            onSuccess = { identity ->
                val explicit =
                    grantStore.state.value
                        .recordFor(identity)
                        ?.grantedCapabilities
                        .orEmpty()

                val storeError =
                    grantStore.loadError.value

                ModuleSecurityStatus(
                    packageName = packageName,
                    identity = identity,
                    sameSignerAsCore =
                        identityResolver
                            .isSameSignerAsCore(identity),
                    requestedCapabilities =
                        requestedCapabilities,
                    explicitGrants = explicit,
                    effectiveGrants =
                        if (storeError == null) {
                            explicit
                                .intersect(
                                    requestedCapabilities,
                                )
                                .intersect(
                                    supportedCapabilities,
                                )
                        } else {
                            emptySet()
                        },
                    unsupportedRequests =
                        requestedCapabilities -
                            supportedCapabilities,
                    error = storeError?.let {
                        "Grant store unavailable: " + it
                    },
                )
            },
            onFailure = { throwable ->
                ModuleSecurityStatus(
                    packageName = packageName,
                    identity = null,
                    sameSignerAsCore = false,
                    requestedCapabilities =
                        requestedCapabilities,
                    explicitGrants = emptySet(),
                    effectiveGrants = emptySet(),
                    unsupportedRequests =
                        requestedCapabilities -
                            supportedCapabilities,
                    error = throwable.message
                        ?: throwable::class.java
                            .simpleName,
                )
            },
        )
    }
}
