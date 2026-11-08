# Category, folder and Mega Sorter acceptance

Status: implementation in the feature branch; CI/device acceptance in progress.
Date: 2026-11-08. Contract: [organization specification](../product/categories-folders-mega-sorter.md).

## Implemented surfaces

- Settings → Categories and folders: master switch, six defaults/custom groups,
  automatic metadata matching, membership overrides and draggable page-order tiles.
- Category pages reuse Home's list/rail/search. Global search includes category-only
  apps. Most Used filters eligibility before limiting suggestions; pins are retained.
- Long press → Add to category: multi-destination chooser and staged new folders/
  categories, with atomic Done and explicit conflict refresh. Folder popups have
  their own settings and typed Most Used pin ordering.
- Manage folders includes hidden entries. Headset, schedule, connected Wi-Fi and
  circular map-area rules use Any/All, with Any default. Location is foreground-only;
  OSM map tiles are requested only by the open map editor and cached. No online
  app-metadata provider, active Wi-Fi scan or background location is introduced.
- Mega Sorter has two directional destinations, fixed row slots, independent
  row animations/writes, precise Undo and a ViewModel lifetime for accepted writes.

## Automated evidence

Local Gradle has passed 134 Core unit tests (zero failures), debug lint, debug app
assembly and Android instrumentation APK assembly. [CI run 137](https://github.com/AlexanderWilhelmsenBerg/Modular/actions/runs/42483176328)
passed the full verification job and 119 of 120 Core device tests. The remaining
picker retry test checked a below-fold error without scrolling; its interaction
was corrected while retaining the visibility assertion. Subsequent exact-commit
results and screenshot review are tracked in [PR #26](https://github.com/AlexanderWilhelmsenBerg/Modular/pull/26).

Focused tests cover classification/cache migration, atomic organization writes,
typed pins, multi-category visibility, per-pair Undo, unknown smart signals,
schedule boundaries, location uncertainty and request budgets. Device tests cover
picker cancellation/retry, hidden-folder management, global search, page navigation
and overlapping sorter gestures. Native screenshot fixtures cover category
settings, popup folders, smart rules and Mega Sorter. [CI run 139](https://github.com/AlexanderWilhelmsenBerg/Modular/actions/runs/42485350607)
passed all new organization device tests. Its 320dp light-theme captures were
reviewed: settings scroll correctly, the folder popup stays bounded, and both long
sorter labels remain visible above the list. An existing add-on revocation test
exposed a synchronization race; it now waits for Home to accept the exact granted
catalog generation before revoking access. The full final run remains tracked in
PR #26. Physical checks below remain separate from emulator evidence.

## Physical acceptance still required

1. On the user's Samsung, enable categories and place Work left of Home and Games
   right. Check swipes, alphabet navigation, search and HOME/Back/settings return.
2. Hide Games from Home; confirm global package/name search still finds games,
   saved pins return when suppression is disabled, and explicit Hide stays hidden.
3. Create folders in both placements. Open, edit, reorder, hide and preview them
   from Settings; unplug a headset while its folder is open and finish using it.
4. Verify wired/USB/Bluetooth outputs, office Wi-Fi with/without internet, denied
   and revoked location permission, Location off, approximate fixes, map offline,
   entering/leaving a saved circle, and overnight schedules/timezone changes.
5. Inspect battery/location usage after normal use and with many folders. Confirm
   no background tracking; check fallback behavior on a device without GMS/network
   location. Foreground evaluation may update a folder shortly after returning Home.
6. Sort consecutive rows while earlier animations run, scroll away/back, Undo,
   rotate and exit. Confirm accepted assignments persist without changing row slots.

No physical Samsung pass, battery measurement, store submission, or Google appeal
is claimed by this record.
