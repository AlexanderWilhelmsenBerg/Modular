# Home widget grid v2 validation

Product contract: [Home widget grid v2](../product/home-widget-grid-v2.md).
The grid baseline was merged in PR #31 as `23cee7489c13`: verification, signed
builds, native API 35 tests and all seven screenshot configurations passed
([CI run](https://github.com/AlexanderWilhelmsenBerg/Modular/actions/runs/42614089377)).
Home/drawer page separation and widget removal passed in PR #32 (`220afe0f3e27`).
The gesture editor and Home action sheet need their own passing CI result.
Record revision and run in the implementing PR; a
baseline pass is not a final-revision or physical-device acceptance claim.

| Area | Implemented check | Execution still needed |
| --- | --- | --- |
| Geometry and packing | `WidgetLayoutTest` covers 4 × 4 plus 1 × 1 placement, saved-position priority, conflicts, column-count reflow, legacy height/provider minimums, rail bounds and vertical bands, including 128 full-width widgets. | Confirm at final CI revision. |
| Provider sizing | `WidgetGridSizingTest` checks advertised 4 × 4 and 1 × 1 spans, older providers without target-cell metadata and a narrower grid. | Run JVM tests and inspect actual provider metadata on device. |
| Persistence | `OrganizationStoreTest` covers grid round-trip, old-record defaults, value limits, column-count shrink and atomic rejection of invalid edits. | Run JVM tests; verify a real existing installation migrates without lost widget IDs. |
| Native rendering | `WidgetGridIntegrationTest` binds real 4 × 4 and 1 × 1 fixture providers, checks measured cells, Android-reported size, drawer separation, saved placement after recreation, long-press edit actions and screenshots. Earlier `WorkspaceScreenshotTest` and `WidgetLayoutEditorTest` cover related hosting and save flows. | Re-run connected tests after the RTL fix, finish all seven matrix runs, inspect captured images and confirm every fixture cleans up its host IDs. |

Native visual review must include 320/375/414/768 dp widths, light/dark/AMOLED
themes, enlarged text, RTL, zero margin/padding, narrow area, a 4 × 4 widget,
several 1 × 1 widgets and a long Home. Verify that the physical right rail is
clear, no widget overlaps, providers receive their actual content sizes, and
the native host respects provider maximum width and height. Check that Home
and All apps are each full-screen at rest, and that the remaining motion in the
same drag crosses the scroll boundary. Check empty-space menu, Advanced > Home
grid, Back to Home and native-widget long-press editing with both a scrollable
provider and one that consumes long presses.
Verify drawer order: Most Used at top when enabled, one Folders section, then
alphabetical apps. With Most Used off, visible Pinned folders must remain in
Folders ahead of Alphabetical folders. Check name sorting, saved manual order,
smart visibility, de-duplication, star and diamond rail destinations, and that
Home contains no Most Used or folder section. Folder settings must expose only
Pinned and Alphabetical ordering choices, with no Show on Home folder option.

On a physical device, repeat consent/configuration cancellation, rotation,
process death, missing-provider recovery and widget move/resize. The existing
[Home workspace validation](home-workspace-validation.md) tracks the broader
navigation, headset, resource and Samsung gesture checks.

For the navigation/removal correction, verify Home swipes only visit Home/widget
pages and drawer swipes only visit All apps/category pages, with independent
selection and settings order. Check restoration, search, rail jumps, deleted
pages and vertical handoff from a category. Long-press a native widget, choose Edit, cancel
Remove widget once, then confirm it: placement and owned host ID must disappear.
A failed save must keep the widget and offer a retry; provider apps stay installed.

For the gesture editor, `WorkspaceWidgetGridTargetTest` checks fixed-top-left
bottom-right resizing and bounds. `HomeActionsSheetTest` checks the three menu
routes and selected widget. Native integration must hold-drag the widget body,
resize right/down from the physical bottom-right, preserve saved geometry, and
confirm no permanent Add widget/Edit Home or nudge controls remain. Review the
Home menu and edit-state screenshots in every matrix configuration, particularly
small widgets, enlarged text and RTL. Record final CI evidence in the PR.

For drawer entry, reopen from a scrolled letter and from a category using a long
upward Home swipe. Central All apps must land at offset zero with Most Used
visible when enabled; remaining opening travel cannot skip it. Verify a fresh
drawer gesture and alphabet navigation still scroll normally.

For section navigation, verify middle and final letters land at the list top
with their normal header height, and that scrolling back reveals the preceding
app without an inserted gap. Manual pull-down reachability remains independent.
For Home settings ordering, verify full-width tile dragging creates a draft,
Save order persists it, a failed save retains it, and widget IDs/spans and
unrelated page placements survive per-page reordering. Capture section and
widget ordering controls across the workspace screenshot matrix.
