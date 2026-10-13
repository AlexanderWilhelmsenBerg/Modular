package io.github.alexanderwilhelmsenberg.modular.security

import io.github.alexanderwilhelmsenberg.modular.moduleapi.CapabilityId
import io.github.alexanderwilhelmsenberg.modular.moduleapi.CoreCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleSecurityManagerTest {
    @Test
    fun officialBootstrapOnlyIncludesRequestedCapabilitiesAndRevocationIsSticky() =
        runBlocking {
            val identity = identity("a")
            val resolver = FakeIdentityResolver(
                currentIdentity = identity,
                sameSignerAsCore = true,
            )
            val store = FakeGrantStore()
            val manager = ModuleSecurityManager(
                identityResolver = resolver,
                grantStore = store,
            )
            val requested = setOf(
                CoreCapabilities.AppsCatalog,
            )

            val authorization = manager.prepareSession(
                packageName = identity.packageName,
                requestedCapabilities = requested,
            )

            assertEquals(
                requested,
                manager.effectiveCapabilities(
                    authorization,
                ),
            )
            assertTrue(
                CoreCapabilities.AppsLaunch !in
                    manager.effectiveCapabilities(
                        authorization,
                    ),
            )

            manager.revoke(
                packageName = identity.packageName,
                capability =
                    CoreCapabilities.AppsCatalog,
            ).getOrThrow()

            assertTrue(
                manager.effectiveCapabilities(
                    authorization,
                ).isEmpty(),
            )

            val reconnected = manager.prepareSession(
                packageName = identity.packageName,
                requestedCapabilities = requested,
            )

            assertTrue(
                manager.effectiveCapabilities(
                    reconnected,
                ).isEmpty(),
            )
            assertTrue(
                store.state.value
                    .recordFor(identity) != null,
            )
        }

    @Test
    fun thirdPartyStartsDeniedAndCanOnlyReceiveRequestedSupportedGrant() =
        runBlocking {
            val identity = identity("b")
            val resolver = FakeIdentityResolver(
                currentIdentity = identity,
                sameSignerAsCore = false,
            )
            val store = FakeGrantStore()
            val manager = ModuleSecurityManager(
                identityResolver = resolver,
                grantStore = store,
            )
            val requested = setOf(
                CoreCapabilities.AppsCatalog,
            )

            val authorization = manager.prepareSession(
                packageName = identity.packageName,
                requestedCapabilities = requested,
            )

            assertTrue(
                manager.effectiveCapabilities(
                    authorization,
                ).isEmpty(),
            )

            manager.grant(
                authorization = authorization,
                capability =
                    CoreCapabilities.AppsCatalog,
            ).getOrThrow()

            assertEquals(
                requested,
                manager.effectiveCapabilities(
                    authorization,
                ),
            )

            val unrequested = manager.grant(
                authorization = authorization,
                capability =
                    CoreCapabilities.AppsLaunch,
            )

            assertTrue(unrequested.isFailure)
        }

    @Test
    fun changedSignerInvalidatesOldAuthorizationAndOldGrant() =
        runBlocking {
            val original = identity("c")
            val replacement = identity("d")
            val resolver = FakeIdentityResolver(
                currentIdentity = original,
                sameSignerAsCore = false,
            )
            val store = FakeGrantStore()
            val manager = ModuleSecurityManager(
                identityResolver = resolver,
                grantStore = store,
            )
            val requested = setOf(
                CoreCapabilities.AppsCatalog,
            )

            val oldAuthorization =
                manager.prepareSession(
                    packageName = original.packageName,
                    requestedCapabilities = requested,
                )

            manager.grant(
                authorization = oldAuthorization,
                capability =
                    CoreCapabilities.AppsCatalog,
            ).getOrThrow()

            assertEquals(
                requested,
                manager.effectiveCapabilities(
                    oldAuthorization,
                ),
            )

            resolver.currentIdentity = replacement

            assertTrue(
                manager.effectiveCapabilities(
                    oldAuthorization,
                ).isEmpty(),
            )

            val newAuthorization =
                manager.prepareSession(
                    packageName =
                        replacement.packageName,
                    requestedCapabilities = requested,
                )

            assertTrue(
                manager.effectiveCapabilities(
                    newAuthorization,
                ).isEmpty(),
            )
        }

    @Test
    fun grantStoreFailureFailsClosedWithoutDiscardingRecordedState() =
        runBlocking {
            val identity = identity("f")
            val resolver = FakeIdentityResolver(
                currentIdentity = identity,
                sameSignerAsCore = false,
            )
            val store = FakeGrantStore(
                initial = ModuleGrantState(
                    records = listOf(
                        ModuleGrantRecord(
                            identity = identity,
                            grantedCapabilities = setOf(
                                CoreCapabilities.AppsCatalog,
                            ),
                        ),
                    ),
                ),
            )
            val manager = ModuleSecurityManager(
                identityResolver = resolver,
                grantStore = store,
            )
            val authorization =
                ModuleAuthorizationContext(
                    identity = identity,
                    requestedCapabilities = setOf(
                        CoreCapabilities.AppsCatalog,
                    ),
                )

            assertEquals(
                setOf(CoreCapabilities.AppsCatalog),
                manager.effectiveCapabilities(
                    authorization,
                ),
            )

            store.fail("disk unavailable")

            assertTrue(
                manager.effectiveCapabilities(
                    authorization,
                ).isEmpty(),
            )
            assertEquals(
                setOf(CoreCapabilities.AppsCatalog),
                store.state.value
                    .recordFor(identity)
                    ?.grantedCapabilities,
            )
            assertTrue(
                manager.status(
                    packageName = identity.packageName,
                    requestedCapabilities =
                        authorization
                            .requestedCapabilities,
                ).error
                    ?.contains("Grant store unavailable")
                    == true,
            )
        }

    @Test
    fun unsupportedRequestIsReportedButNeverEffective() =
        runBlocking {
            val identity = identity("e")
            val unsupported = CapabilityId(
                name = "modular.future.example",
                majorVersion = 1,
            )
            val resolver = FakeIdentityResolver(
                currentIdentity = identity,
                sameSignerAsCore = true,
            )
            val store = FakeGrantStore()
            val manager = ModuleSecurityManager(
                identityResolver = resolver,
                grantStore = store,
            )
            val requested = setOf(
                unsupported,
                CoreCapabilities.AppsCatalog,
            )

            val authorization =
                manager.prepareSession(
                    packageName = identity.packageName,
                    requestedCapabilities = requested,
                )

            assertEquals(
                setOf(CoreCapabilities.AppsCatalog),
                manager.effectiveCapabilities(
                    authorization,
                ),
            )

            val status = manager.status(
                packageName = identity.packageName,
                requestedCapabilities = requested,
            )

            assertEquals(
                setOf(unsupported),
                status.unsupportedRequests,
            )
        }

    private fun identity(
        fill: String,
    ) = ModuleIdentity(
        packageName = PACKAGE_NAME,
        signerSha256 =
            listOf(fill.repeat(64)),
    )

    private class FakeIdentityResolver(
        var currentIdentity: ModuleIdentity,
        var sameSignerAsCore: Boolean,
    ) : ModuleIdentityResolver {
        override fun resolve(
            packageName: String,
        ): Result<ModuleIdentity> =
            if (
                packageName ==
                currentIdentity.packageName
            ) {
                Result.success(currentIdentity)
            } else {
                Result.failure(
                    IllegalArgumentException(
                        "Unknown package " + packageName,
                    ),
                )
            }

        override fun isSameSignerAsCore(
            identity: ModuleIdentity,
        ): Boolean =
            sameSignerAsCore &&
                identity == currentIdentity
    }

    private class FakeGrantStore(
        initial: ModuleGrantState =
            ModuleGrantState(),
    ) : ModuleGrantStateStore {
        private val mutableState =
            MutableStateFlow(initial)
        private val mutableLoadError =
            MutableStateFlow<String?>(null)

        override val state:
            StateFlow<ModuleGrantState> =
            mutableState

        override val loadError:
            StateFlow<String?> =
            mutableLoadError

        fun fail(message: String) {
            mutableLoadError.value = message
        }

        override suspend fun update(
            transform:
                (ModuleGrantState) ->
                    ModuleGrantState,
        ): ModuleGrantState {
            val next =
                transform(mutableState.value)
            mutableState.value = next
            return next
        }

        override fun close() = Unit
    }

    companion object {
        private const val PACKAGE_NAME =
            "example.module"
    }
}
