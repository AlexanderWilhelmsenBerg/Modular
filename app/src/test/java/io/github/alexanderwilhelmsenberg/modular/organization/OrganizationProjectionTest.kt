package io.github.alexanderwilhelmsenberg.modular.organization

import android.content.pm.ApplicationInfo
import io.github.alexanderwilhelmsenberg.modular.apps.AppCatalog
import io.github.alexanderwilhelmsenberg.modular.apps.AppCustomizations
import io.github.alexanderwilhelmsenberg.modular.apps.AppKey
import io.github.alexanderwilhelmsenberg.modular.apps.AppProfile
import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrganizationProjectionTest {
    private val game = app("game", ApplicationInfo.CATEGORY_GAME)
    private val media = app("media", ApplicationInfo.CATEGORY_AUDIO)
    private val unknown = app("unknown", null)

    @Test fun androidMappingIsPartialAndLegacyGameFlagOnlyFillsUndefined() {
        assertEquals(ApplicationInfo.CATEGORY_GAME,
            CategoryClassifier.categorySignal(ApplicationInfo.CATEGORY_UNDEFINED, true))
        assertEquals(ApplicationInfo.CATEGORY_NEWS,
            CategoryClassifier.categorySignal(ApplicationInfo.CATEGORY_NEWS, true))
        assertTrue(CategoryClassifier.matches(BuiltInCategories.GAMES, game))
        assertTrue(CategoryClassifier.matches(BuiltInCategories.MEDIA, media))
        assertFalse(CategoryClassifier.matches(BuiltInCategories.FINANCE, media))
        assertFalse(CategoryClassifier.matches(BuiltInCategories.WORK, unknown))
    }

    @Test fun overrideTruthTableAndAnyHideWinsWithoutChangingSearchOrFolderContents() {
        val categories = BuiltInCategories.defaults.map {
            if (it.id == BuiltInCategories.GAMES || it.id == BuiltInCategories.MEDIA)
                it.copy(enabled = true, showOnHome = false) else it
        }
        val folder = FolderDefinition("folder", "My folder", listOf(game.key, media.key))
        val base = OrganizationSnapshot(categoriesEnabled = true, categories = categories,
            folders = listOf(folder), pins = listOf(HomePinRef.App(game.key)))
        val catalog = AppCatalog(profiles = listOf(AppProfile(1, false)),
            apps = listOf(game, media, unknown))
        val hidden = AppCustomizations(hidden = setOf(media.key.stableId))
        val initial = OrganizationProjector.project(catalog, hidden, base)
        assertEquals(listOf(unknown.key), initial.homeApps.map { it.key })
        assertEquals(listOf(game.key, unknown.key), initial.globalSearchApps.map { it.key })
        assertEquals(listOf(game.key), initial.appListFolders.single().apps.map { it.key })
        assertEquals(listOf(media.key), initial.appListFolders.single().unavailableMembers)
        assertEquals(listOf(game.key), initial.suppressedPinnedApps)

        val excluded = base.copy(overrides = mapOf(
            MembershipKey(BuiltInCategories.GAMES, game.key) to MembershipOverride.EXCLUDE,
            MembershipKey(BuiltInCategories.MEDIA, game.key) to MembershipOverride.INCLUDE,
        ))
        assertTrue(CategoryClassifier.membership(excluded, categories.first { it.id == BuiltInCategories.MEDIA }, game))
        assertFalse(CategoryClassifier.membership(excluded, categories.first { it.id == BuiltInCategories.GAMES }, game))
        assertEquals(listOf(unknown.key), OrganizationProjector.project(catalog, hidden, excluded)
            .homeApps.map { it.key })
        assertEquals(listOf(game.key, unknown.key), OrganizationProjector.project(catalog, hidden,
            excluded.copy(categoriesEnabled = false)).homeApps.map { it.key })
    }

    @Test fun smartFalseAndUnknownFallbackAreDistinct() {
        val smart = FolderDefinition("smart", "Smart", visibilityMode = FolderVisibilityMode.SMART)
        val catalog = AppCatalog(profiles = listOf(AppProfile(1, false)), apps = listOf(game))
        val snapshot = OrganizationSnapshot(folders = listOf(smart))
        assertEquals(1, OrganizationProjector.project(catalog, AppCustomizations(), snapshot).appListFolders.size)
        assertTrue(OrganizationProjector.project(catalog, AppCustomizations(), snapshot,
            mapOf("smart" to false)).appListFolders.isEmpty())
        assertTrue(OrganizationProjector.project(catalog, AppCustomizations(),
            snapshot.copy(folders = listOf(smart.copy(unknownFallback = UnknownFallback.HIDE))))
            .appListFolders.isEmpty())
    }

    @Test fun physicalPageOrderKeepsDisabledSlotsWithoutShowingThem() {
        val categories = BuiltInCategories.defaults.map {
            when (it.id) {
                BuiltInCategories.WORK, BuiltInCategories.GAMES -> it.copy(enabled = true)
                else -> it
            }
        }
        val order = listOf(HomePageId.Category(BuiltInCategories.WORK), HomePageId.Home,
            HomePageId.Category(BuiltInCategories.GAMES)) +
            BuiltInCategories.ids.filterNot {
                it == BuiltInCategories.WORK || it == BuiltInCategories.GAMES
            }.map(HomePageId::Category)
        val snapshot = OrganizationSnapshot(categoriesEnabled = true, categories = categories,
            pageOrder = order)
        assertEquals(listOf(HomePageId.Category(BuiltInCategories.WORK), HomePageId.Home,
            HomePageId.Category(BuiltInCategories.GAMES)), snapshot.visiblePageOrder())
        assertEquals(listOf(HomePageId.Home), snapshot.copy(categoriesEnabled = false).visiblePageOrder())
    }

    @Test fun unavailableRowsRemainInHomeAndSearchButNotLaunchableFolderOrPins() {
        val paused = app("paused", ApplicationInfo.CATEGORY_GAME).copy(enabled = false)
        val quiet = app("quiet", ApplicationInfo.CATEGORY_GAME).copy(
            key = AppKey("example.quiet", "quiet.Main", 2))
        val catalog = AppCatalog(profiles = listOf(AppProfile(1, false), AppProfile(2, true)),
            apps = listOf(paused, quiet))
        val snapshot = OrganizationSnapshot(folders = listOf(FolderDefinition("f", "Folder",
            members = listOf(paused.key, quiet.key))),
            pins = listOf(HomePinRef.App(paused.key), HomePinRef.App(quiet.key)))
        val projection = OrganizationProjector.project(catalog, AppCustomizations(), snapshot)
        assertEquals(2, projection.homeApps.size)
        assertEquals(2, projection.globalSearchApps.size)
        assertTrue(projection.appListFolders.single().apps.isEmpty())
        assertEquals(2, projection.appListFolders.single().unavailableMembers.size)
        assertTrue(projection.visiblePinnedApps.isEmpty())
        assertTrue(projection.filterDirectStarred(listOf(paused, quiet)).isEmpty())
    }

    private fun app(id: String, category: Int?) = LaunchableApp(
        AppKey("example.$id", "$id.Main", 1), id, 0, true, null, category,
    )
}
