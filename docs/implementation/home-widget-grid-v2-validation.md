# Home widget grid v2 validation

Product contract: [Home widget grid v2](../product/home-widget-grid-v2.md).
The checks below are implemented in the repository. At the previous head, all
148 native connected tests passed and six of seven screenshot-matrix runs
passed. The subsequent RTL fix passed local compilation and lint; final CI and
the seventh matrix run remain pending. Record revision, command, device/API
level and result for final execution. A prior-head pass is not a final-revision
or physical-device acceptance claim.

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
