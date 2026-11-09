package io.github.alexanderwilhelmsenberg.modular.ui

import android.view.WindowManager
import android.content.res.Configuration
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.alexanderwilhelmsenberg.modular.apps.AppCatalog
import io.github.alexanderwilhelmsenberg.modular.apps.AppKey
import io.github.alexanderwilhelmsenberg.modular.apps.AppProfile
import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

class CoreHomeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun matchHomeWindowInsets() {
        composeRule.activityRule.scenario.onActivity { activity ->
            // Match MainActivity: the keyboard resizes the available viewport;
            // automatic window panning must not move the first result off-screen.
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
            activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    @Test
    fun appsAreSortedAlphabeticallyAndEnabledRowsLaunchTheSelectedApp() {
        val launched = mutableListOf<String>()
        val apps = listOf(
            app("zeta.pkg", "Zeta"),
            app("alpha.pkg", "Alpha"),
            app("middle.pkg", "Middle"),
        )

        composeRule.setContent {
            HomeScreen(
                catalog = AppCatalog(apps = apps),
                onLaunch = { launched += it.label },
                onRefresh = {},
                onMakeDefault = {},
                onOpenSettings = {},
            )
        }

        composeRule.onNodeWithText("Alpha").assertIsDisplayed()
        composeRule.onNodeWithText("Middle").assertIsDisplayed()
        composeRule.onNodeWithText("Zeta").assertIsDisplayed()
        val topPositions = listOf("Alpha", "Middle", "Zeta").map {
            composeRule.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.top
        }
        assertEquals("Home apps should be alphabetized", topPositions.sorted(), topPositions)

        composeRule.onNodeWithTag("app-row:0:alpha.pkg/alpha.pkg.MainActivity").performClick()
        composeRule.runOnIdle { assertEquals(listOf("Alpha"), launched) }
    }

    @Test
    fun settingsAndMakeDefaultActionsInvokeTheirCallbacks() {
        var openedSettings = false
        var madeDefault = false

        composeRule.setContent {
            HomeScreen(
                catalog = AppCatalog(),
                onLaunch = {},
                onRefresh = {},
                onMakeDefault = { madeDefault = true },
                onOpenSettings = { openedSettings = true },
            )
        }

        composeRule.onNodeWithTag("home-settings")
            .assertContentDescriptionEquals("Settings")
            .performClick()
        composeRule.onNodeWithTag("home-make-default").performClick()
        composeRule.runOnIdle {
            assertEquals(true, openedSettings)
            assertEquals(true, madeDefault)
        }
    }

    @Test
    fun searchFiltersAppsAndClearRestoresAlphabeticalList() {
        val apps = listOf(
            app("zeta.pkg", "Zeta"),
            app("alpha.pkg", "Alpha"),
            app("beta.pkg", "Beta"),
        )
        composeRule.setContent {
            HomeScreen(
                catalog = AppCatalog(apps = apps),
                onLaunch = {}, onRefresh = {}, onMakeDefault = {}, onOpenSettings = {},
            )
        }

        composeRule.onNodeWithText("Apps").assertDoesNotExist()
        composeRule.onNodeWithTag("home-search-field").assertContentDescriptionEquals("Search apps").performClick()
        composeRule.onNodeWithTag("home-search-field")
            .assertContentDescriptionEquals("Search apps")
            .performTextInput("bet")
        composeRule.onNodeWithText("Beta").assertIsDisplayed()
        composeRule.onNodeWithText("Alpha").assertDoesNotExist()
        composeRule.onNodeWithText("Zeta").assertDoesNotExist()

        composeRule.onNodeWithTag("home-search-clear")
            .assertContentDescriptionEquals("Clear search")
            .performClick()
        listOf("Alpha", "Beta", "Zeta").forEach {
            composeRule.onNodeWithText(it).assertIsDisplayed()
        }
        val positions = listOf("Alpha", "Beta", "Zeta").map {
            composeRule.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.top
        }
        assertEquals(positions.sorted(), positions)
    }

    @Test
    fun unmatchedSearchHasDistinctEmptyStateAndSettingsStaysReachable() {
        var settingsOpens = 0
        composeRule.setContent {
            HomeScreen(
                catalog = AppCatalog(apps = listOf(app("alpha.pkg", "Alpha"))),
                onLaunch = {}, onRefresh = {}, onMakeDefault = {},
                onOpenSettings = { settingsOpens += 1 },
            )
        }

        composeRule.onNodeWithTag("home-search-field").performClick()
        composeRule.onNodeWithTag("home-search-field").performTextInput("missing")
        composeRule.onNodeWithText("No matching apps").assertIsDisplayed()
        composeRule.onNodeWithText("No apps available").assertDoesNotExist()
        composeRule.onNodeWithTag("home-settings")
            .assertIsDisplayed()
            .assertContentDescriptionEquals("Settings")
            .performClick()
        composeRule.runOnIdle { assertEquals(1, settingsOpens) }
    }

    @Test
    fun persistentSearchFillsRowAndKeepsBoundsWhenFocusedOrTyped() {
        composeRule.setContent {
            HomeScreen(
                catalog = AppCatalog(),
                onLaunch = {}, onRefresh = {}, onMakeDefault = {}, onOpenSettings = {},
            )
        }

        val density = InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.displayMetrics.density
        val restingSearch = composeRule.onNodeWithTag("home-search").fetchSemanticsNode().boundsInRoot
        val settings = composeRule.onNodeWithTag("home-settings").fetchSemanticsNode().boundsInRoot
        assertEquals(48f, restingSearch.height / density, 1.5f)
        assertEquals(48f, settings.height / density, 1.5f)
        assertEquals(48f, settings.width / density, 1.5f)
        assertEquals(restingSearch.height, settings.height, density)
        composeRule.onNodeWithTag("home-search-field").assertIsDisplayed()
        assertTrue("Search should fill the space beside Settings", restingSearch.width > settings.width * 3)
        composeRule.onNodeWithTag("home-search-field").performClick()
        composeRule.onNodeWithTag("home-search-field").performTextInput("A long query that scrolls while the search bar stays the same size")
        val focused = composeRule.onNodeWithTag("home-search").fetchSemanticsNode().boundsInRoot
        assertEquals(restingSearch.width, focused.width, 1f)
        assertEquals(restingSearch.height, focused.height, 1f)
        composeRule.onNodeWithTag("home-search-clear").performClick()
        val cleared = composeRule.onNodeWithTag("home-search").fetchSemanticsNode().boundsInRoot
        assertEquals(restingSearch.width, cleared.width, 1f)
        assertEquals(restingSearch.height, cleared.height, 1f)
        composeRule.onNodeWithTag("home-settings").assertIsDisplayed()
    }

    @Test
    fun compactWidthAndLargeTextKeepSearchAndSettingsUsable() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                Box(Modifier.width(320.dp)) {
                    HomeScreen(
                        catalog = AppCatalog(apps = listOf(app("alpha.pkg", "Alpha"))),
                        onLaunch = {}, onRefresh = {}, onMakeDefault = {}, onOpenSettings = {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag("home-search-field").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("home-search-field").assertIsDisplayed().performTextInput("Alpha")
        val alphaKey = app("alpha.pkg", "Alpha").key.stableId
        composeRule.onNodeWithTag("app-row:$alphaKey").assertIsDisplayed()
        composeRule.onNodeWithTag("home-settings").assertIsDisplayed()
    }

    @Test
    fun disabledAppsAndQuietProfilesCannotBeLaunched() {
        val launches = mutableListOf<String>()
        val disabled = app("disabled.pkg", "Disabled", enabled = false)
        val quietProfileApp = app("work.pkg", "Work", profile = 10L)

        composeRule.setContent {
            HomeScreen(
                catalog = AppCatalog(
                    profiles = listOf(AppProfile(serialNumber = 10L, quietModeEnabled = true)),
                    apps = listOf(disabled, quietProfileApp),
                ),
                onLaunch = { launches += it.label },
                onRefresh = {},
                onMakeDefault = {},
                onOpenSettings = {},
            )
        }

        composeRule.onNodeWithTag("app-row:${disabled.key.stableId}").assertIsNotEnabled()
        composeRule.onNodeWithTag("app-row:${quietProfileApp.key.stableId}").assertIsNotEnabled()
        composeRule.onNodeWithText("Some apps are unavailable while a profile is paused.")
            .assertIsDisplayed()
        assertEquals(
            "Unavailable",
            composeRule.onNodeWithTag("app-row:${disabled.key.stableId}")
                .fetchSemanticsNode().config[SemanticsProperties.StateDescription],
        )
        assertEquals(
            "Profile paused",
            composeRule.onNodeWithTag("app-row:${quietProfileApp.key.stableId}")
                .fetchSemanticsNode().config[SemanticsProperties.StateDescription],
        )
        composeRule.runOnIdle { assertEquals(emptyList<String>(), launches) }
    }

    @Test
    fun loadingCatalogShowsProgress() {
        composeRule.setContent {
            HomeScreen(
                catalog = AppCatalog(isRefreshing = true),
                onLaunch = {},
                onRefresh = {},
                onMakeDefault = {},
                onOpenSettings = {},
            )
        }
        composeRule.onNodeWithText("Loading apps…").assertIsDisplayed()
    }

    @Test
    fun emptyCatalogExplainsThatNoAppsAreAvailable() {
        composeRule.setContent {
            HomeScreen(
                catalog = AppCatalog(),
                onLaunch = {},
                onRefresh = {},
                onMakeDefault = {},
                onOpenSettings = {},
            )
        }
        composeRule.onNodeWithText("No apps available").assertIsDisplayed()
    }

    @Test
    fun drawerWithFoldersDoesNotClaimItHasNoContent() {
        composeRule.setContent {
            HomeScreen(
                catalog = AppCatalog(),
                folders = listOf(io.github.alexanderwilhelmsenberg.modular.organization.FolderDefinition(
                    "visible-folder", "My folder")),
                onLaunch = {}, onRefresh = {}, onMakeDefault = {}, onOpenSettings = {},
            )
        }
        composeRule.onNodeWithTag("section:Folders").assertIsDisplayed()
        composeRule.onNodeWithText("My folder").assertIsDisplayed()
        composeRule.onNodeWithText("No apps available").assertDoesNotExist()
    }

    @Test
    fun catalogErrorCanBeRetried() {
        var refreshes = 0
        composeRule.setContent {
            HomeScreen(
                catalog = AppCatalog(lastError = "Repository unavailable"),
                onLaunch = {},
                onRefresh = { refreshes += 1 },
                onMakeDefault = {},
                onOpenSettings = {},
            )
        }

        composeRule.onNodeWithText("Apps could not be loaded").assertIsDisplayed()
        composeRule.onNodeWithText("Repository unavailable").assertIsDisplayed()
        composeRule.onNodeWithTag("home-retry").performClick()
        composeRule.runOnIdle { assertEquals(1, refreshes) }
    }

    @Test
    fun defaultRowsContainOnlyAppNameText() {
        val entry = app("plain.pkg", "Plain app")
        composeRule.setContent {
            HomeScreen(
                catalog = AppCatalog(apps = listOf(entry), profiles = listOf(AppProfile(0L, false))),
                onLaunch = {}, onRefresh = {}, onMakeDefault = {}, onOpenSettings = {},
            )
        }
        composeRule.onAllNodes(
            hasAnyAncestor(hasTestTag("app-row:${entry.key.stableId}")) and
                SemanticsMatcher.keyIsDefined(SemanticsProperties.Text),
            useUnmergedTree = true,
        ).assertCountEquals(1)
        composeRule.onAllNodes(
            hasAnyAncestor(hasTestTag("app-row:${entry.key.stableId}")) and
                SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription),
            useUnmergedTree = true,
        ).assertCountEquals(0)
    }

    @Test
    fun sectionHeadersAndRailUseSameLocaleBuckets() {
        val builder = HomeSectionBuilder(Locale.forLanguageTag("nb-NO"))
        val apps = listOf(app("a.pkg", "Åpen"), app("o.pkg", "Økonomi"), app("ae.pkg", "Ærlig"), app("num.pkg", "123"))
        val sections = builder.sections(apps)
        assertEquals(listOf("#", "Æ", "Ø", "Å"), sections.map { it.label })
        assertTrue(builder.alphabet.containsAll(listOf("#", "Æ", "Ø", "Å")))
    }

    @Test
    fun nonLatinAndDuplicateLabelsRemainComplete() {
        val builder = HomeSectionBuilder(Locale.forLanguageTag("el-GR"))
        val apps = listOf(
            app("greek.one", "Αθήνα"),
            app("greek.two", "Αθήνα"),
            app("greek.omega", "Ωμέγα"),
            app("numeric.one", "2FA"),
        )
        val sections = builder.sections(apps)
        assertEquals(apps.map { it.key }.toSet(), sections.flatMap { it.apps }.map { it.key }.toSet())
        assertEquals(apps.size, sections.sumOf { it.apps.size })
        assertTrue(builder.bucket("Αθήνα") != "#")
        assertEquals("#", builder.bucket("2FA"))
    }

    @Test
    fun bottomControlsStayBelowSectionedRowsAndSearchHidesRail() {
        val listState = LazyListState()
        composeRule.setContent {
            HomeScreen(
                catalog = AppCatalog(apps = listOf(app("alpha.pkg", "Alpha"), app("beta.pkg", "Beta"))),
                listState = listState,
                onLaunch = {}, onRefresh = {}, onMakeDefault = {}, onOpenSettings = {},
            )
        }
        composeRule.onNodeWithTag("section:A").assertIsDisplayed()
        composeRule.onNodeWithTag("section:B").assertIsDisplayed()
        composeRule.onNodeWithTag("alphabet-rail").assertIsDisplayed()
        val searchTop = composeRule.onNodeWithTag("home-search").fetchSemanticsNode().boundsInRoot.top
        val betaBottom = composeRule.onNodeWithText("Beta").fetchSemanticsNode().boundsInRoot.bottom
        assertTrue("Search is fixed beneath the list", searchTop > betaBottom)
        composeRule.onNodeWithTag("home-search-field").performTextInput("bet")
        composeRule.onNodeWithTag("alphabet-rail").assertDoesNotExist()
        try {
            composeRule.onNodeWithText("Beta").assertIsDisplayed()
        } catch (failure: AssertionError) {
            val visible = listState.layoutInfo.visibleItemsInfo.joinToString { "${it.index}:${it.key}@${it.offset}+${it.size}" }
            val betaBounds = runCatching { composeRule.onNodeWithText("Beta").fetchSemanticsNode().boundsInRoot }.getOrNull()
            val listBounds = composeRule.onNodeWithTag("home-list").fetchSemanticsNode().boundsInRoot
            val rootBounds = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
            throw AssertionError("Beta hidden after search: first=${listState.firstVisibleItemIndex}/${listState.firstVisibleItemScrollOffset}, visible=[$visible], beta=$betaBounds, list=$listBounds, root=$rootBounds").apply { initCause(failure) }
        }
    }

    @Test
    fun searchFromRetainedLateKeyShowsFirstResultAndRestoresAnchor() {
        val listState = LazyListState()
        val apps = listOf("Alpha", "Beta", "Zeta").flatMap { label ->
            (1..40).map { app("${label.lowercase()}$it.pkg", "$label ${it.toString().padStart(2, '0')}") }
        }
        composeRule.setContent {
            HomeScreen(
                catalog = AppCatalog(apps = apps), listState = listState,
                onLaunch = {}, onRefresh = {}, onMakeDefault = {}, onOpenSettings = {},
                showMakeDefault = false,
            )
        }
        composeRule.onNodeWithTag("home-search-field").performClick()
        composeRule.runOnIdle { listState.requestScrollToItem(70) }
        composeRule.waitForIdle()
        val oldIndex = listState.firstVisibleItemIndex
        assertTrue(oldIndex > 1)
        composeRule.onNodeWithTag("home-search-field").performTextInput("beta")
        composeRule.onNodeWithText("Beta 01").assertIsDisplayed()
        composeRule.runOnIdle { assertTrue("Filtered result must start at the list top", listState.firstVisibleItemIndex <= 1) }
        composeRule.onNodeWithTag("home-search-clear").performClick()
        composeRule.runOnIdle { assertEquals(oldIndex, listState.firstVisibleItemIndex) }
    }

    @Test
    fun emptyFeaturedSectionOffersPinAndPermissionActions() {
        var openedPins = 0
        var openedPermission = 0
        composeRule.setContent {
            HomeScreen(
                catalog = AppCatalog(apps = listOf(app("alpha.pkg", "Alpha"))),
                onLaunch = {}, onRefresh = {}, onMakeDefault = {}, onOpenSettings = {},
                featuredApps = emptyList(),
                featuredNeedsPermission = true,
                onOpenFeaturedSettings = { openedPins++ },
                onOpenFeaturedManagement = { openedPermission++ },
            )
        }
        composeRule.onNodeWithTag("section:★").assertIsDisplayed()
        composeRule.onNodeWithTag("featured-settings").performClick()
        composeRule.onNodeWithTag("featured-permission").performClick()
        composeRule.runOnIdle {
            assertEquals(1, openedPins)
            assertEquals(1, openedPermission)
        }
    }

    @Test
    fun railContainsOnlyPopulatedSectionsAndTapsThemDirectly() {
        val apps = (1..12).map { app("a$it.pkg", "Alpha $it") } + app("charlie.pkg", "Charlie")
        composeRule.setContent {
            HomeScreen(
                catalog = AppCatalog(apps = apps),
                onLaunch = {}, onRefresh = {}, onMakeDefault = {}, onOpenSettings = {},
                showMakeDefault = false,
            )
        }
        composeRule.onNodeWithTag("rail:B").assertDoesNotExist()
        composeRule.onNodeWithTag("rail:#").assertDoesNotExist()
        composeRule.onNodeWithTag("rail:Ø").assertDoesNotExist()
        tapVisibleRailLetter("C")
        composeRule.onNodeWithTag("section:C").assertIsDisplayed()
        composeRule.onNodeWithText("Choose section").assertDoesNotExist()
    }

    @Test
    fun localeRailUpdatesWhenItsLastAppDisappears() {
        val apps = mutableStateOf(listOf(
            app("alpha.pkg", "Alpha"), app("ae.pkg", "Ærlig"),
            app("oe.pkg", "Øvelse"), app("aa.pkg", "Åpen"),
        ))
        composeRule.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                setLocales(LocaleList(Locale.forLanguageTag("nb-NO")))
            }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                HomeScreen(AppCatalog(apps = apps.value), {}, {}, {}, {}, showMakeDefault = false)
            }
        }
        listOf("A", "Æ", "Ø", "Å").forEach { letter ->
            composeRule.onNodeWithTag("rail:$letter").assertIsDisplayed()
            tapVisibleRailLetter(letter)
            composeRule.onNodeWithTag("section:$letter").assertIsDisplayed()
        }
        composeRule.onNodeWithTag("rail:B").assertDoesNotExist()
        composeRule.onNodeWithTag("rail:#").assertDoesNotExist()
        composeRule.runOnIdle { apps.value = apps.value.filter { it.label == "Alpha" } }
        listOf("Æ", "Ø", "Å").forEach { composeRule.onNodeWithTag("rail:$it").assertDoesNotExist() }
        tapVisibleRailLetter("A")
        composeRule.onNodeWithTag("section:A").assertIsDisplayed()
    }

    @Test
    fun packageSearchFindsAppWhoseLabelDoesNotMatch() {
        composeRule.setContent {
            HomeScreen(
                AppCatalog(apps = listOf(app("com.nvidia.shieldtv", "SHIELD TV"), app("other.pkg", "Other"))),
                {}, {}, {}, {}, showMakeDefault = false,
            )
        }
        composeRule.onNodeWithTag("home-search-field").performTextInput("Nvidia")
        composeRule.onNodeWithText("SHIELD TV").assertIsDisplayed()
        composeRule.onNodeWithText("Other").assertDoesNotExist()
        composeRule.onNodeWithTag("alphabet-rail").assertDoesNotExist()
    }

    @Test
    fun cancelledRailGestureRemovesBubbleWithoutLaunchingApp() {
        var launches = 0
        composeRule.setContent {
            HomeScreen(
                catalog = AppCatalog(apps = listOf(app("alpha.pkg", "Alpha"), app("zeta.pkg", "Zeta"))),
                onLaunch = { launches++ }, onRefresh = {}, onMakeDefault = {}, onOpenSettings = {},
                showMakeDefault = false,
            )
        }
        val lane = composeRule.onNodeWithTag("alphabet-hit-lane")
        lane.performTouchInput {
            down(Offset(width / 2f, height * 0.5f - 14f))
            moveTo(Offset(width / 2f, height * 0.5f), 120L)
        }
        composeRule.onNodeWithTag("alphabet-bubble").assertIsDisplayed()
        val bubbleBounds = composeRule.onNodeWithTag("alphabet-bubble").fetchSemanticsNode().boundsInRoot
        val laneBounds = lane.fetchSemanticsNode().boundsInRoot
        assertTrue("Selected-letter bubble stays left of the touch lane", bubbleBounds.right < laneBounds.left)
        lane.performTouchInput { cancel() }
        composeRule.onNodeWithTag("alphabet-bubble").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(0, launches) }
    }

    @Test
    fun largeFontRailKeepsEveryLetterVisibleAndTappable() {
        var launches = 0
        val apps = ('A'..'Z').flatMap { letter ->
            (1..3).map { app("fixture.$letter.$it", "$letter app $it") }
        }
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2.2f)) {
                Box(Modifier.width(320.dp).height(500.dp)) {
                    HomeScreen(
                        catalog = AppCatalog(apps = apps),
                        onLaunch = { launches++ }, onRefresh = {}, onMakeDefault = {}, onOpenSettings = {},
                        showMakeDefault = false,
                    )
                }
            }
        }
        val locale = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.locales[0]
        HomeSectionBuilder(locale).sections(apps).map { it.label }.forEach { letter ->
            composeRule.onNodeWithTag("rail:$letter").assertIsDisplayed()
        }
        listOf("Z", "B", "Z").forEach { letter ->
            tapVisibleRailLetter(letter)
            composeRule.onNodeWithTag("section:$letter").assertIsDisplayed()
            composeRule.onNodeWithText("Choose section").assertDoesNotExist()
        }
        composeRule.runOnIdle { assertEquals(0, launches) }
    }

    @Test
    fun compactRailTapMapMatchesDisplayedStarNumbersAndLocaleLetters() {
        val alphabet = listOf("★", "#") + ('A'..'Z').map(Char::toString) + listOf("Æ", "Ø", "Å")
        val selected = mutableListOf<String>()
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                Box(Modifier.width(320.dp).height(500.dp)) {
                    AlphabetRail(alphabet, alphabet.toSet(), { selected.add(it) }, false)
                }
            }
        }
        // Every glyph is present, including formerly omitted odd slots B and Ø.
        // Tap each rendered center to exercise the displayed position and hit map.
        var previousCenter = Float.NEGATIVE_INFINITY
        alphabet.forEach { letter ->
            val bounds = composeRule.onNodeWithTag("rail:$letter").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("Rail labels must retain distinct ordered slots", bounds.center.y > previousCenter)
            previousCenter = bounds.center.y
            tapVisibleRailLetter(letter)
            composeRule.runOnIdle { assertEquals(letter, selected.last()) }
            composeRule.onNodeWithText("Choose section").assertDoesNotExist()
        }
    }

    @Test
    fun shortLargeFontRailStillShowsAllLocaleLabels() {
        val alphabet = listOf("★", "#") + ('A'..'Z').map(Char::toString) + listOf("Æ", "Ø", "Å")
        val selected = mutableListOf<String>()
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2.2f)) {
                Box(Modifier.width(320.dp).height(280.dp)) {
                    AlphabetRail(alphabet, alphabet.toSet(), { selected.add(it) }, false)
                }
            }
        }
        alphabet.forEach { letter -> composeRule.onNodeWithTag("rail:$letter").assertIsDisplayed() }
        listOf("★", "#", "B", "D", "Ø", "Å").forEach { letter ->
            tapVisibleRailLetter(letter)
            composeRule.runOnIdle { assertEquals(letter, selected.last()) }
        }
    }

    @Test
    fun fullRailTapsNavigateDirectly() {
        val alphabet = listOf("★", "#", "A", "B", "C", "D")
        val selected = mutableListOf<String>()
        composeRule.setContent {
            Box(Modifier.width(320.dp).height(500.dp)) {
                AlphabetRail(alphabet, alphabet.toSet(), { selected.add(it) }, false)
            }
        }
        alphabet.forEach { letter ->
            tapVisibleRailLetter(letter)
            composeRule.runOnIdle { assertEquals(letter, selected.last()) }
            composeRule.onNodeWithText("Choose section").assertDoesNotExist()
        }
    }

    private fun tapVisibleRailLetter(letter: String) {
        val label = composeRule.onNodeWithTag("rail:$letter").fetchSemanticsNode().boundsInRoot
        val lane = composeRule.onNodeWithTag("alphabet-hit-lane")
        val laneBounds = lane.fetchSemanticsNode().boundsInRoot
        lane.performTouchInput {
            down(Offset(width / 2f, label.center.y - laneBounds.top))
            up()
        }
    }

    @Test
    fun thousandAppsRemainCompleteAndLastSectionIsReachable() {
        val apps = (0 until 1000).map { index ->
            app("fixture$index.pkg", "${('A'.code + index % 26).toChar()} app $index")
        }
        val locale = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.locales[0]
        val sections = HomeSectionBuilder(locale).sections(apps)
        assertEquals(1000, sections.sumOf { it.apps.size })
        composeRule.setContent {
            HomeScreen(
                catalog = AppCatalog(apps = apps),
                onLaunch = {}, onRefresh = {}, onMakeDefault = {}, onOpenSettings = {},
                showMakeDefault = false, showAppIcons = false,
            )
        }
        val alphabet = HomeSectionBuilder(locale).sections(apps).map { it.label }
        val zIndex = alphabet.indexOf("Z")
        assertTrue(zIndex >= 0)
        composeRule.onNodeWithTag("alphabet-hit-lane").performTouchInput {
            val y = height * (zIndex + 0.5f) / alphabet.size
            down(Offset(width / 2f, y - 14f))
            moveTo(Offset(width / 2f, y), 120L)
            up()
        }
        composeRule.onNodeWithTag("section:Z").assertIsDisplayed()
    }

    @Test
    fun railJumpLeavesBlankSpaceAboveSelectedHeader() {
        val apps = (0..25).flatMap { letter ->
            (1..5).map { number ->
                val prefix = ('A'.code + letter).toChar()
                app("fixture.$letter.$number", "$prefix sample $number")
            }
        }
        composeRule.setContent {
            HomeScreen(
                catalog = AppCatalog(apps = apps),
                onLaunch = {}, onRefresh = {}, onMakeDefault = {}, onOpenSettings = {},
                showMakeDefault = false, showAppIcons = false,
            )
        }
        val locale = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.locales[0]
        val alphabet = HomeSectionBuilder(locale).sections(apps).map { it.label }
        val kIndex = alphabet.indexOf("K")
        assertTrue(kIndex >= 0)
        composeRule.onNodeWithTag("alphabet-hit-lane").performTouchInput {
            val y = height * (kIndex + 0.5f) / alphabet.size
            down(Offset(width / 2f, y - 14f))
            moveTo(Offset(width / 2f, y), 120L)
            up()
        }
        val viewport = composeRule.onNodeWithTag("home-list").fetchSemanticsNode().boundsInRoot
        val header = composeRule.onNodeWithTag("section:K").fetchSemanticsNode().boundsInRoot
        val relativeTop = (header.top - viewport.top) / viewport.height
        assertTrue("Selected header should sit near the upper third: $relativeTop", relativeTop in 0.25f..0.47f)
        composeRule.onNodeWithText("J sample 5").assertDoesNotExist()
    }

    @Test
    fun clearingSearchRestoresPreviousListAnchor() {
        val state = LazyListState()
        val apps = (1..32).map { app("alpha$it.pkg", "Alpha $it") }
        composeRule.setContent {
            HomeScreen(
                catalog = AppCatalog(apps = apps), listState = state,
                onLaunch = {}, onRefresh = {}, onMakeDefault = {}, onOpenSettings = {},
                showMakeDefault = false,
            )
        }
        composeRule.onNodeWithTag("home-search-field").performClick()
        composeRule.runOnIdle { state.requestScrollToItem(20) }
        composeRule.waitForIdle()
        val previousIndex = state.firstVisibleItemIndex
        assertTrue(previousIndex > 0)
        composeRule.onNodeWithTag("home-search-field").performTextInput("Alpha 1")
        composeRule.onNodeWithTag("home-search-clear").performClick()
        composeRule.runOnIdle { assertEquals(previousIndex, state.firstVisibleItemIndex) }
    }

    private fun app(
        packageName: String,
        label: String,
        enabled: Boolean = true,
        profile: Long = 0L,
    ) = LaunchableApp(
        key = AppKey(packageName, "$packageName.MainActivity", profile),
        label = label,
        firstInstallTime = 1L,
        enabled = enabled,
        loadingProgress = null,
    )
}
