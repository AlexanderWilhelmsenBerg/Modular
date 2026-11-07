package io.github.alexanderwilhelmsenberg.modular.moduleapi

import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppKeyParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppSortItemParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.FeaturedAppsRequestParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.FeaturedAppsResultParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.FeaturedPinOrderRequestParcel
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class FeaturedAppsContractTest {
    private val installed = AppKeyParcel("example.installed", "Main", 1)
    private val missing = AppKeyParcel("example.missing", "Main", 1)
    private val another = AppKeyParcel("example.another", "Main", 2)
    private val request = FeaturedAppsRequestParcel(
        "read-1",
        7,
        "nb-NO",
        listOf(AppSortItemParcel(installed, "Installed"), AppSortItemParcel(another, "Another")),
    )

    @Test fun savedUnavailablePinIsAllowedButUnknownSuggestionIsRejected() {
        FeaturedAppsContract.validateResult(request, result(pins = listOf(missing), suggestions = listOf(installed)))
        rejects { FeaturedAppsContract.validateResult(request, result(suggestions = listOf(missing))) }
    }

    @Test fun duplicateAndStaleResultsAreRejected() {
        rejects { FeaturedAppsContract.validateResult(request, result(pins = listOf(installed, installed))) }
        rejects { FeaturedAppsContract.validateResult(request, result(pins = listOf(installed), suggestions = listOf(installed))) }
        rejects { FeaturedAppsContract.validateResult(request, result().copy(catalogRevision = 6)) }
        rejects { FeaturedAppsContract.validateResult(request, result().copy(requestId = "read-0")) }
    }

    @Test fun pinOrderIncludesEverySavedPinExactlyOnce() {
        val saved = listOf(installed, missing)
        FeaturedAppsContract.validatePinOrder(
            FeaturedPinOrderRequestParcel("order-1", 4, listOf(missing, installed)),
            saved,
        )
        rejects {
            FeaturedAppsContract.validatePinOrder(
                FeaturedPinOrderRequestParcel("order-1", 4, listOf(installed)),
                saved,
            )
        }
        rejects {
            FeaturedAppsContract.validatePinOrder(
                FeaturedPinOrderRequestParcel("order-1", 4, listOf(installed, installed)),
                saved,
            )
        }
    }

    @Test fun catalogLimitIsExplicitAndSortCapabilityIsUnchanged() {
        assertEquals(2_000, FeaturedAppsRequestParcel.MAX_ITEMS)
        assertEquals("modular.apps.featured/v1", StandardCapabilities.AppsFeatured.wireValue)
        assertEquals("modular.apps.sort/v1", StandardCapabilities.AppsSort.wireValue)
        rejects {
            FeaturedAppsContract.validateRequest(
                request.copy(items = List(FeaturedAppsRequestParcel.MAX_ITEMS + 1) {
                    AppSortItemParcel(AppKeyParcel("example.$it", "Main", 1), "Label")
                }),
            )
        }
    }

    private fun result(
        pins: List<AppKeyParcel> = emptyList(),
        suggestions: List<AppKeyParcel> = emptyList(),
    ) = FeaturedAppsResultParcel(
        requestId = "read-1",
        catalogRevision = 7,
        featureRevision = 4,
        status = FeaturedAppsResultParcel.STATUS_SUCCESS,
        featureState = FeaturedAppsResultParcel.STATE_READY,
        pinnedKeys = pins,
        suggestedKeys = suggestions,
        message = null,
    )

    private inline fun rejects(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
