package io.github.alexanderwilhelmsenberg.modular.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.alexanderwilhelmsenberg.modular.apps.AppCatalog
import io.github.alexanderwilhelmsenberg.modular.apps.AppKey
import io.github.alexanderwilhelmsenberg.modular.apps.AppRepository
import io.github.alexanderwilhelmsenberg.modular.capabilities.CoreHostBinder
import io.github.alexanderwilhelmsenberg.modular.moduleapi.CoreCapabilities
import io.github.alexanderwilhelmsenberg.modular.moduleapi.capabilities.IAppCatalogCapability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoreHostGrantRevocationTest {
    @Test
    fun issuedCapabilityBinderFailsImmediatelyAfterGrantRevocation() =
        runBlocking {
            val context =
                ApplicationProvider
                    .getApplicationContext<Context>()
            val identity = ModuleIdentity(
                packageName = context.packageName,
                signerSha256 =
                    listOf("a".repeat(64)),
            )
            val store = FakeGrantStore(
                ModuleGrantState(
                    records = listOf(
                        ModuleGrantRecord(
                            identity = identity,
                            grantedCapabilities =
                                setOf(
                                    CoreCapabilities
                                        .AppsCatalog,
                                ),
                        ),
                    ),
                ),
            )
            val resolver =
                FixedIdentityResolver(identity)
            val manager = ModuleSecurityManager(
                identityResolver = resolver,
                grantStore = store,
            )
            val authorization =
                ModuleAuthorizationContext(
                    identity = identity,
                    requestedCapabilities =
                        setOf(
                            CoreCapabilities
                                .AppsCatalog,
                        ),
                )
            val binder = CoreHostBinder(
                context = context,
                expectedPackage =
                    context.packageName,
                sessionId = "test-session",
                appRepository =
                    FakeAppRepository(),
                securityManager = manager,
                authorization = authorization,
            )

            assertArrayEquals(
                arrayOf(
                    CoreCapabilities
                        .AppsCatalog
                        .wireValue,
                ),
                binder.capabilities,
            )

            val rawCapability =
                binder.getCapabilityBinder(
                    CoreCapabilities
                        .AppsCatalog
                        .wireValue,
                )
            assertNotNull(rawCapability)

            val catalog =
                IAppCatalogCapability.Stub
                    .asInterface(rawCapability)

            catalog.info

            store.update { state ->
                state.copy(
                    records = listOf(
                        ModuleGrantRecord(
                            identity = identity,
                            grantedCapabilities =
                                emptySet(),
                        ),
                    ),
                )
            }

            assertArrayEquals(
                emptyArray<String>(),
                binder.capabilities,
            )
            assertNull(
                binder.getCapabilityBinder(
                    CoreCapabilities
                        .AppsCatalog
                        .wireValue,
                ),
            )

            assertThrows(
                SecurityException::class.java,
            ) {
                catalog.info
            }
        }

    private class FixedIdentityResolver(
        private val identity: ModuleIdentity,
    ) : ModuleIdentityResolver {
        override fun resolve(
            packageName: String,
        ): Result<ModuleIdentity> =
            if (packageName == identity.packageName) {
                Result.success(identity)
            } else {
                Result.failure(
                    IllegalArgumentException(
                        "Unexpected package",
                    ),
                )
            }

        override fun isSameSignerAsCore(
            identity: ModuleIdentity,
        ): Boolean = false
    }

    private class FakeGrantStore(
        initial: ModuleGrantState,
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

    private class FakeAppRepository :
        AppRepository {
        private val state =
            MutableStateFlow(AppCatalog())

        override val catalog:
            StateFlow<AppCatalog> =
            state

        override fun refresh() = Unit

        override suspend fun launch(
            key: AppKey,
        ): Result<Unit> =
            Result.success(Unit)

        override fun close() = Unit
    }
}
