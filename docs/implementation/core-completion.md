# Core completion

The Core APK provides its own launcher and Settings without optional modules,
network access, accounts or configuration. The original delivery completed Home
controls and built-in themes and connected optional sort helpers to Home. Home
presentation v2 extends that baseline with icons, indexed navigation, optional
wallpaper, and generic featured-app contributions.

## Original Core delivery plan and ownership

- Home agent: persistent full-width search, accessible gear, current-catalog filtering
  and text-only app rows.
- Appearance agent: persistent themes, Settings selector, system bars, contrast
  coverage and bounded future theme contract.
- Helper agent: bounded Binder invocation, valid-current-result application,
  fallback and retained failure diagnostics; independent reference helper setting.
- Validation agent: navigation/search/compact-layout tests and native Hallmark review.
- Coordinator: source integration, CI repair, specification reconciliation, signing
  workflow verification and merge after successful checks.

## Original Core requirements and evidence

These rows describe the original delivery. The v2 acceptance matrix below
supersedes its text-only presentation and top control placement.

| Current requirement | Implementation and coverage |
| --- | --- |
| Home first, complete text-only catalog, exact launch identity | Existing catalog visibility, Home and navigation tests; app objects always come from Core. |
| Alphabetical baseline and local search | HomeSearchTest and CoreHomeTest cover locale matching, clearing, no results, duplicate identity, stale helper order and current metadata. |
| Gear and persistent search | CoreHomeTest covers semantics, equal 48 dp baseline height, unchanged field bounds on focus/typing/clearing, compact widths and large font. |
| Back, Settings, rotation and new HOME | CoreNavigationTest covers saved query, Settings recreation, Back while editing and explicit HOME reset. |
| Persisted Light/Dark/AMOLED | AppearanceSettingsTest covers persistence, radio semantics, true-black surfaces and text/control contrast. |
| Optional helper integration and recovery | HomeSortControllerTest covers selected order, current Core objects, timeout, invalid response, route loss, deactivation and late response rejection; cross-APK smoke coverage verifies selected helper order and deactivation/disable fallback through real Binder negotiation. |
| Module lifecycle, diagnostics, grants, uninstall | Existing registry/security/management tests plus compact diagnostic filter and switch accessibility tests; paused-profile notice/state and privacy-safe diagnostics; physical uninstall confirmation remains an Android device check. |
| Theme extensibility boundary | Versioned, bounded theme-contract.md defines validation and fallback before any external loader is introduced. |

## Reference helper proof

Install the matching debug or release reference sort APK. In Core Settings, enable
its service and open **Settings** on the module card. Toggle **Reverse alphabetical order**, then
select **Use in App drawer**. Change the switch and **Save settings** to compare ordering.
Select **Use alphabetical order** to restore Core ordering. No instance or context
needs to be created for this ordinary path.

Protocol v4 still delivers each instance's normalized configuration when acquiring
its endpoint. **Advanced configuration** exposes instances and contexts for custom
composition, while ordinary settings manage a dedicated instance and Home binding.
A disabled, hung, failed or invalid helper falls back and records a bounded
diagnostic event without preventing app launching or Settings.

## Validation status

### Home presentation v2 implementation and acceptance

The v2 implementation is delivered through [PR #19](https://github.com/AlexanderWilhelmsenBerg/Modular/pull/19).
Its head-specific Android CI checks and Signed APKs workflow are the authoritative
automated results. The earlier Core baseline evidence does not establish v2
physical-device acceptance.

Local checks on 2026-11-07 passed 95 unit tests across Core, API, SDK and Most Used;
Core/add-on Kotlin and Android test compilation; Core/add-on lint; and six signing
verifier tests. The final integration checks used JDK 17, Gradle 9.6.0 and Android
SDK 37.0. Runtime CI uses an API 35 emulator. Home captures cover 320/375/411/414/768 dp,
large text, light/dark/AMOLED, generated wallpaper, the active rail and retained
pull-down state. A capture is evidence to review, not proof of physical-device parity.

| Gate | Implementation and automated evidence | Status |
| --- | --- | --- |
| V2-A1 | `app/src/main/java/io/github/alexanderwilhelmsenberg/modular/ui/HomeSectionBuilder.kt`, `HomeAppList.kt`, `HomeBottomControls.kt`, `apps/AppIconRepository.kt`; Core-only emulator install and catalog completeness check | Implemented; Core catalog/navigation checks run in PR #19. |
| V2-A2 | `app/src/main/java/io/github/alexanderwilhelmsenberg/modular/ui/AlphabetRail.kt`, `HomeScreen.kt`; rest and active rail captures at 320/375/414/768 dp and large font | Implemented; PNG/MP4 artifacts are generated by PR #19 CI for visual review. |
| V2-A3 | `app/src/main/java/io/github/alexanderwilhelmsenberg/modular/appearance/AppearanceSettingsStore.kt`, `HomeAppearanceControls.kt`, `WallpaperHomeScrim.kt`; `HomeAppearanceSettingsTest`, `HomeAppearanceControlsTest`, `HomeAppearancePersistenceTest` | Implemented with persistence/control tests; OEM and live-wallpaper checks remain physical acceptance. |
| V2-A4 | `app/src/main/java/io/github/alexanderwilhelmsenberg/modular/ui/AlphabetRail.kt`, `HomeSectionBuilder.kt`; locale, missing-bucket, drag, cancel, RTL, rotation and reduced-motion scenarios | Implemented with locale, missing-bucket, large-text, cancellation and 1,000-app instrumentation; physical gesture acceptance remains. |
| V2-A5 | `app/src/main/java/io/github/alexanderwilhelmsenberg/modular/ui/HomeReachability.kt`, `HomeScreen.kt`, `HomeAppList.kt`; pull, retained offset, upward restore, HOME/search reset and launch checks | Implemented with retained-offset/tappable-row and reset coverage; physical Samsung acceptance remains. |
| V2-A6 | `app/src/main/java/io/github/alexanderwilhelmsenberg/modular/ui/HomeBottomControls.kt`, `HomeSearch.kt`, `HomeScreen.kt`; IME/search/last-row checks | Implemented with search/IME/activity-navigation coverage in PR #19. |
| V2-A7 | `module-api/src/main/aidl/io/github/alexanderwilhelmsenberg/modular/moduleapi/capabilities/IFeaturedAppsCapability.aidl`, `app/src/main/java/io/github/alexanderwilhelmsenberg/modular/home/HomeFeaturedController.kt`, `addon-most-used/src/main/java/io/github/alexanderwilhelmsenberg/modular/addon/sort/mostused/MostUsedSortService.kt`; `FeaturedAppsCrossApkTest`, `MostUsedSorterTest` | Real separate-APK tests cover positive usage, pin ordering and permission revocation in PR #19. |
| V2-A8 | `app/src/main/java/io/github/alexanderwilhelmsenberg/modular/ui/FeaturedAppSettingsUi.kt`, `HomeAppList.kt`, `addon-most-used/src/main/java/io/github/alexanderwilhelmsenberg/modular/addon/sort/mostused/PinState.kt`, `PinStore.kt`; `PinStateTest`, `PinStoreTest` | Pin editor, denied-access, persisted-pin and corrupt-store tests are present; physical profile/OEM checks remain. |
| V2-A9 | `module-api/src/main/java/io/github/alexanderwilhelmsenberg/modular/moduleapi/FeaturedAppsContract.kt`, `module-sdk/src/main/java/io/github/alexanderwilhelmsenberg/modular/modulesdk/FeaturedAppsEndpoint.kt`, `app/src/main/java/io/github/alexanderwilhelmsenberg/modular/capabilities/FeaturedAppsClient.kt`; `HomeFeaturedControllerTest`, `FeaturedAppsCrossApkTest` | PR #19 tests include legacy sort, new featured IPC, malformed parcels and fresh-session persistence. |
| V2-A10 | `app/src/main/java/io/github/alexanderwilhelmsenberg/modular/apps/AppIconRepository.kt`, `ui/AlphabetRail.kt`, `HomeAppList.kt`; 1,000-app and rapid-scrub profiling | Bounded icon/IPC work and 1,000-app fixture coverage; physical frame-time profiling remains. |

Paths above start at the repository root; subsequent bare filenames in each
cell share the preceding full file's directory unless a new directory is named.
The test source roots are `app/src/test/java/io/github/alexanderwilhelmsenberg/modular/`,
`app/src/androidTest/java/io/github/alexanderwilhelmsenberg/modular/`,
`addon-most-used/src/test/java/io/github/alexanderwilhelmsenberg/modular/addon/sort/mostused/`
and `addon-most-used/src/androidTest/java/io/github/alexanderwilhelmsenberg/modular/addon/sort/mostused/`.
The
[implementation plan](home-presentation-v2-plan.md#4-current-implementation-map)
maps the work packages. Record exact test commands, revision and results after CI,
then attach measured screenshots/video and physical Samsung observations.

PR #11 passed build/unit/lint and API 35 emulator CI before merging at
`bc070fdaee380fa6810e996157bc38b5d5e392ec`.

The original Core completion change is tracked in [PR #12](https://github.com/AlexanderWilhelmsenBerg/Modular/pull/12). Its commit-specific checks are the authoritative evidence for that earlier revision. The v2 toolchain and verification results are recorded separately above.

## Physical-device acceptance

The owner reports the non-add-on checks in the device checklist passed: Home navigation,
search and keyboard behavior, screen-edge reachability, built-in themes and persistence,
reboot recovery, and installed-app updates. This is user-reported evidence on the tested
device, not a claim about every OEM or configuration. TalkBack is deferred by the owner;
work-profile tests are not part of this personal-device acceptance. Profile creation and
management are outside Core; Core retains existing profile identity for correct launching.
Add-on activation, disable/fallback, and uninstall cancellation/success remain pending
because the reference APK was not installed. The reported malformed search glyph is
corrected separately from the passing search behavior.


Validate Android HOME role selection, actual app launches, long queries with the
system keyboard, TalkBack focus, all themes in gesture/three-button navigation,
large fonts, work-profile pause/resume, process death and OEM package updates.
Cancel Android uninstall and confirm the package/configuration remains; then confirm
uninstall and check that discovery updates while saved unresolved references remain.
These checks are device-dependent and are not claimed complete by unit tests.

## Explicitly later add-ons

External Home/Drawer rendering, external theme loading, richer configuration schemas,
profile labels, filtering providers, automation, scripts, MCP and
terminal integrations remain later add-ons. They must preserve baseline Core recovery.
Icons are now Core presentation. The later [ADR-0006](../decisions/ADR-0006-core-most-used-and-feature-order.md)
moves ordinary pins and usage suggestions into Core; the generic featured-app
capability remains for reference and compatible providers. Earlier acceptance
evidence in this document belongs to its recorded revision.

## Core framework contract

[Core framework completion](core-framework.md) supplies protocol-v4 instance
configuration, validated manifest descriptors, a reusable public SDK, and independent
add-on build/runtime acceptance. [Product specification section 17](../product/product-spec.md#17-core-framework-and-remaining-acceptance)
tracks the resulting contract and remaining physical-device acceptance. Build/unit/lint
and emulator results are recorded against PR #14; remaining device checks are tracked above.
