# Core completion

The Core APK provides its own launcher and Settings without optional modules,
network access, accounts or configuration. This delivery completes the agreed
Home controls and built-in themes and connects optional sort helpers to Home.

## Coordinator plan and ownership

- Home agent: persistent full-width search, accessible gear, current-catalog filtering
  and text-only app rows.
- Appearance agent: persistent themes, Settings selector, system bars, contrast
  coverage and bounded future theme contract.
- Helper agent: bounded Binder invocation, valid-current-result application,
  fallback and retained failure diagnostics; independent reference helper setting.
- Validation agent: navigation/search/compact-layout tests and native Hallmark review.
- Coordinator: source integration, CI repair, specification reconciliation, signing
  workflow verification and merge after successful checks.

## Requirements and evidence

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
select **Use on Home**. Change the switch and **Save settings** to compare ordering.
Select **Use alphabetical order** to restore Core ordering. No instance or context
needs to be created for this ordinary path.

Protocol v4 still delivers each instance's normalized configuration when acquiring
its endpoint. **Advanced configuration** exposes instances and contexts for custom
composition, while ordinary settings manage a dedicated instance and Home binding.
A disabled, hung, failed or invalid helper falls back and records a bounded
diagnostic event without preventing app launching or Settings.

## Validation status

### Home presentation v2 implementation and acceptance

The v2 source changes are present in this workspace. They have not yet passed
revision-specific CI, emulator, cross-APK, visual, or physical-device acceptance.
The earlier Core baseline evidence above does not establish any v2 result.

| Gate | Source and proposed test evidence | Status |
| --- | --- | --- |
| V2-A1 | `app/src/main/java/io/github/alexanderwilhelmsenberg/modular/ui/HomeSectionBuilder.kt`, `HomeAppList.kt`, `HomeBottomControls.kt`, `apps/AppIconRepository.kt`; Core-only emulator install and catalog completeness check | Source present; CI/emulator pending. |
| V2-A2 | `app/src/main/java/io/github/alexanderwilhelmsenberg/modular/ui/AlphabetRail.kt`, `HomeScreen.kt`; rest and active rail captures at 320/375/414/768 dp and large font | Source present; screenshots and reference comparison pending. |
| V2-A3 | `app/src/main/java/io/github/alexanderwilhelmsenberg/modular/appearance/AppearanceSettingsStore.kt`, `HomeAppearanceControls.kt`, `WallpaperHomeScrim.kt`; `HomeAppearanceSettingsTest`, `HomeAppearanceControlsTest`, `HomeAppearancePersistenceTest` | Source and tests present; CI and bright/busy/dark/live wallpaper device checks pending. |
| V2-A4 | `app/src/main/java/io/github/alexanderwilhelmsenberg/modular/ui/AlphabetRail.kt`, `HomeSectionBuilder.kt`; locale, missing-bucket, drag, cancel, RTL, rotation and reduced-motion scenarios | Source present; CI, emulator and device gesture evidence pending. |
| V2-A5 | `app/src/main/java/io/github/alexanderwilhelmsenberg/modular/ui/HomeReachability.kt`, `HomeScreen.kt`, `HomeAppList.kt`; pull, retained offset, upward restore, HOME/search reset and launch checks | Source present; gesture tests, emulator and physical Samsung checks pending. |
| V2-A6 | `app/src/main/java/io/github/alexanderwilhelmsenberg/modular/ui/HomeBottomControls.kt`, `HomeSearch.kt`, `HomeScreen.kt`; IME/search/last-row checks | Source present; CI and emulator navigation-mode checks pending. |
| V2-A7 | `module-api/src/main/aidl/io/github/alexanderwilhelmsenberg/modular/moduleapi/capabilities/IFeaturedAppsCapability.aidl`, `app/src/main/java/io/github/alexanderwilhelmsenberg/modular/home/HomeFeaturedController.kt`, `addon-most-used/src/main/java/io/github/alexanderwilhelmsenberg/modular/addon/sort/mostused/MostUsedSortService.kt`; `FeaturedAppsCrossApkTest`, `MostUsedSorterTest` | Source and tests present; separate-APK positive-usage and pin-order acceptance pending. |
| V2-A8 | `app/src/main/java/io/github/alexanderwilhelmsenberg/modular/ui/FeaturedAppSettingsUi.kt`, `HomeAppList.kt`, `addon-most-used/src/main/java/io/github/alexanderwilhelmsenberg/modular/addon/sort/mostused/PinState.kt`, `PinStore.kt`; `PinStateTest`, `PinStoreTest` | Source and tests present, including direct Home Usage Access action; UI, denied/revoked, unavailable-profile, corruption and restart checks pending. |
| V2-A9 | `module-api/src/main/java/io/github/alexanderwilhelmsenberg/modular/moduleapi/FeaturedAppsContract.kt`, `module-sdk/src/main/java/io/github/alexanderwilhelmsenberg/modular/modulesdk/FeaturedAppsEndpoint.kt`, `app/src/main/java/io/github/alexanderwilhelmsenberg/modular/capabilities/FeaturedAppsClient.kt`; `HomeFeaturedControllerTest`, `FeaturedAppsCrossApkTest` | Source and tests present; CI, old/new APK and adversarial runtime checks pending. |
| V2-A10 | `app/src/main/java/io/github/alexanderwilhelmsenberg/modular/apps/AppIconRepository.kt`, `ui/AlphabetRail.kt`, `HomeAppList.kt`; 1,000-app and rapid-scrub profiling | Source present; performance/device evidence pending. |

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

The Core completion change is tracked in [PR #12](https://github.com/AlexanderWilhelmsenBerg/Modular/pull/12). Its commit-specific checks are the authoritative Android build, unit/lint, and emulator evidence. Local execution is unavailable in the source mirror because Gradle, Android SDK and ADB are absent. Source-only review is not device verification.

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
favorites, icons/profile labels, filtering providers, automation, scripts, MCP and
terminal integrations remain later add-ons. They must preserve baseline Core recovery.

## Core framework contract

[Core framework completion](core-framework.md) supplies protocol-v4 instance
configuration, validated manifest descriptors, a reusable public SDK, and independent
add-on build/runtime acceptance. [Product specification section 17](../product/product-spec.md#17-core-framework-and-remaining-acceptance)
tracks the resulting contract and remaining physical-device acceptance. Build/unit/lint
and emulator results are recorded against PR #14; remaining device checks are tracked above.
