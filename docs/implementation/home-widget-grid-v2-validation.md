# Home widget grid v2 validation

Product contract: [Home widget grid v2](../product/home-widget-grid-v2.md).
The checks below are implemented in the repository; their presence does not
mean they have passed on an emulator or physical device. Record the revision,
command, device/API level and result when executing them.
Local JVM tests and compilation passed before the latest host sizing changes;
lint and connected emulator tests remain pending for the final revision.

| Area | Implemented check | Execution still needed |
| --- | --- | --- |
| Geometry and packing | `WidgetLayoutTest` covers 4 × 4 plus 1 × 1 placement, saved-position priority, conflicts, column-count reflow, legacy height/provider minimums, rail bounds and vertical bands, including 128 full-width widgets. | Run JVM tests in CI and review failures against current layout source. |
| Provider sizing | `WidgetGridSizingTest` checks advertised 4 × 4 and 1 × 1 spans, older providers without target-cell metadata and a narrower grid. | Run JVM tests and inspect actual provider metadata on device. |
| Persistence | `OrganizationStoreTest` covers grid round-trip, old-record defaults, value limits, column-count shrink and atomic rejection of invalid edits. | Run JVM tests; verify a real existing installation migrates without lost widget IDs. |
| Native rendering | `WidgetGridIntegrationTest` binds real 4 × 4 and 1 × 1 fixture providers, checks measured cells, Android-reported size, drawer separation, saved placement after recreation, long-press edit actions and screenshots. Earlier `WorkspaceScreenshotTest` and `WidgetLayoutEditorTest` cover related hosting and save flows. | Run connected tests on an emulator, inspect captured images and confirm every fixture cleans up its host IDs. |

Native visual review must include 320/375/414/768 dp widths, light/dark/AMOLED
themes, enlarged text, RTL, zero margin/padding, narrow area, a 4 × 4 widget,
several 1 × 1 widgets and a long Home. Verify that the physical right rail is
clear, no widget overlaps, providers receive their actual content sizes, and
the native host respects provider maximum width and height. Check that Home
and All apps are each full-screen at rest, and that the remaining motion in the
same drag crosses the scroll boundary. Check empty-space menu, Advanced > Home
grid, Back to Home and native-widget long-press editing with both a scrollable
provider and one that consumes long presses.

On a physical device, repeat consent/configuration cancellation, rotation,
process death, missing-provider recovery and widget move/resize. The existing
[Home workspace validation](home-workspace-validation.md) tracks the broader
navigation, headset, resource and Samsung gesture checks.
