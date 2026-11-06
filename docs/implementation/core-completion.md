# Core completion

The Core APK provides its own launcher and Settings without optional modules,
network access, accounts or configuration. This delivery completes the agreed
Home controls and built-in themes and connects optional sort helpers to Home.

## Coordinator plan and ownership

- Home agent: compact expanding search, accessible gear, current-catalog filtering
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
| Gear and expanding search | CoreHomeTest covers semantics, equal 48 dp resting height, expansion, compact widths and large font. |
| Back, Settings, rotation and new HOME | CoreNavigationTest covers saved query, Settings recreation, Back while editing and explicit HOME reset. |
| Persisted Light/Dark/AMOLED | AppearanceSettingsTest covers persistence, radio semantics, true-black surfaces and text/control contrast. |
| Optional helper integration and recovery | HomeSortControllerTest covers selected order, current Core objects, timeout, invalid response, route loss, deactivation and late response rejection; cross-APK smoke coverage verifies selected helper order and deactivation/disable fallback through real Binder negotiation. |
| Module lifecycle, diagnostics, grants, uninstall | Existing registry/security/management tests plus compact diagnostic filter and switch accessibility tests; paused-profile notice/state and privacy-safe diagnostics; physical uninstall confirmation remains an Android device check. |
| Theme extensibility boundary | Versioned, bounded theme-contract.md defines validation and fallback before any external loader is introduced. |

## Reference helper proof

Install the matching debug or release reference sort APK. In Core Settings, inspect
and enable its service; create an instance for `modular.reference.sort.alphabetical`,
then a context binding `apps.sort/v1` to that instance and activate it. The provider
is optional: deactivating the context returns Home to Core alphabetical order.

Open the reference APK's own launcher entry and enable reverse order. Return to
Core: the refreshed catalog is ordered by that helper. This is a module-wide demo
preference, not per-instance configuration: the current sort wire request carries
apps and locale, not instance settings. Disable the module or deactivate the context
to restore Core ordering. A hung, failed or invalid helper also falls back and
records a bounded diagnostic event without preventing app launching or Settings.

## Validation status

PR #11 passed build/unit/lint and API 35 emulator CI before merging at
`bc070fdaee380fa6810e996157bc38b5d5e392ec`.

The Core completion change is tracked in [PR #12](https://github.com/AlexanderWilhelmsenBerg/Modular/pull/12). Its commit-specific checks are the authoritative Android build, unit/lint, and emulator evidence. Local execution is unavailable in the source mirror because Gradle, Android SDK and ADB are absent. Source-only review is not device verification.

## Physical-device acceptance

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
