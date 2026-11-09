# Home workspace v1 validation

Product contract: [Home workspace v1](../product/home-workspace-v1.md), acceptance IDs WS-A01–WS-A10.

This file records executable evidence and remaining device checks. A passing
unit test proves only its model-level rule; the connected tests exercise the
real Activity on an emulator. Screenshots are visual review evidence, not pixel
assertions. Do not mark a gate complete from a screenshot alone.

## Automated checks

| Gate | Evidence |
| --- | --- |
| WS-A01, WS-A03 | `WorkspaceVerticalStateTest` checks boundary remainder, reversal, cancellation target, downward clamp, and that fling velocity alone cannot open or close the list. `WorkspaceNavigationTest` checks real Home and All apps drag handoff in both directions, plus Apps, Back, HOME, rail, and recreation. |
| WS-A02 | Connected rail test targets a populated All apps section from Home; the existing `CoreHomeTest` verifies the legacy rail selection curve and list positioning. |
| WS-A04, WS-A05 | Widget stage-journal tests and `WorkspaceScreenshotTest` use a real bound RemoteViews provider and verify placement across Activity recreation. `WorkspaceConfiguredWidgetTest` exercises successful configuration, cancelled new configuration and cancelled reconfiguration. Consent denial and provider disappearance need explicit device runs. |
| WS-A06 | Organization store tests exercise versioned page/widget persistence and category migration; `WorkspaceSettingsTest` and `WorkspaceScreenManagerTest` check page order, names, creation and deletion through the user route. |
| WS-A07 | Listening projection tests cover choice, hide and headset-type decisions. Physical output switching remains a device check. |
| WS-A09 | `scripts/capture-workspace.py` captures a 320/375/414/768dp matrix, enlarged font, light/dark/AMOLED, RTL and a real fixture widget where supported by the emulator. Files are uploaded by Android CI for visual review. |
| WS-A10 | Android CI runs the full unit, lint, assemble and connected test suites. Legacy `HomeScreen` fixtures intentionally continue to exercise the isolated list component. |

## Widget layout extension

Contract: [Widget layout](../product/widget-layout.md). `WidgetLayoutTest` covers
ordered row packing, full-row interruption, narrow fallback and edge geometry.
Organization store tests cover old-record defaults, round trips and validation.
`WorkspaceScreenshotTest` binds real providers, checks paired cell bounds and
reported dimensions, zero-margin edge-to-rail layout, narrower area settings and
Activity recreation. The existing capture matrix includes these widget layouts.
`WidgetLayoutEditorTest` covers explicit-save and failure behavior in the editors.
Run results and screenshot review are recorded in the implementing pull request;
these test descriptions alone are not a claim that device acceptance is complete.

The later [Home widget grid v2](../product/home-widget-grid-v2.md) supersedes
the paired-row behavior. Its added model, storage and native test inventory,
plus unexecuted device checks, are in the
[grid validation record](home-widget-grid-v2-validation.md).
The latest drawer-order decision puts Most Used, one combined Folders section,
then alphabetical apps in All apps. Home contains no Most Used or folders.
Check both rail destinations and confirm visible Pinned folders remain in the
drawer when Most Used is disabled; the grid validation record tracks final CI.

## Device acceptance still required

- On a Samsung device with gesture navigation, drag a scrollable widget and a
  noncooperative widget; verify widget touch ownership, launcher gutters, same
  drag boundary handoff and no accidental HOME/back gesture interception.
- Bind a real widget requiring system consent and one requiring provider
  configuration. Cancel each flow, rotate, and kill/relaunch during the external
  Activity. Confirm no orphan host IDs and no lost existing placement.
- Disconnect/reconnect wired, USB and Bluetooth headset outputs while Home is
  visible and while another app is foreground. Confirm Listening visibility and
  ordering after return, without a prompt for Usage Access.
- Inspect the exported screenshots at every size and font scale, including a
  bright/busy wallpaper, safe insets and RTL layout. Check clipped labels,
  touch targets and third-party widget rendering by eye.
- Compare idle and page-switch resource traces before claiming any battery
  benefit. Confirm hidden pages do not keep widget host views actively mounted.

Record the APK revision, Android build, device model, navigation mode and
pass/fail notes when completing those physical checks.
