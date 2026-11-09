# Home workspace implementation plan

Status: **Implementation authorized on 2026-11-08; integration and acceptance in progress.**
Product contract: [Home workspace v1](../product/home-workspace-v1.md).

## Verified integration points

The existing code provides alphabetical Home/list/rail components, global search,
exact AppKey launching, built-in Most Used, category page order, folders, and
theme/window handling. `FolderConditionCoordinator` already subscribes to
`AudioDeviceCallback` and classifies wired, USB and Bluetooth headset outputs.
`OrganizationModels`/`OrganizationStore` currently constrain page order to one
Home plus categories. That model needs a migration, not a second page-order store.
The module model describes future external surfaces; the implemented protocol v4
does not supply a generic external-page renderer. Native widget hosting is new.

Authoritative baseline is `fff68d16de7aa615f9725cefd48b7c8c9ff1cf7b` (PR #28).
Repowise indexed/live head matched that baseline with no lag and a partial index;
jCodemunch symbol results were cross-checked against workspace source. These
tools identified integration points, not runtime clearance. See the
[acceptance record](home-workspace-validation.md) for actual test evidence.

## Proposed architecture

- `WorkspaceState`: stable Home/category/widget-page identities and one page order.
- `LauncherNavigationState`: workspace origin, All apps/search overlay and per-page
  anchors, with explicit HOME/Back and gesture arbitration policies. A shared
  vertical transition joins Home's bottom to All apps' top, consuming scroll
  before boundary drag and keeping Home above All apps throughout the motion.
- `WorkspaceLayoutStore`: versioned section/widget placement with atomic saves and
  validation; migrate organization page identities without discarding settings.
- `WidgetHostController`: Android AppWidgetHost lifecycle, Activity-result flows,
  pending bind/configuration journal and reconciliation of allocated IDs.
- `WidgetHostView` bridge: AndroidView hosting with provider-compatible sizing;
  launcher-owned edit controls outside the widget's own touch surface.
- `ListeningAppsController`: persisted ordered AppKeys and trigger preferences;
  subscribes to shared foreground audio state, without owning another collector.
- `WorkspacePager` and `AllAppsSurface`: reuse existing list/section/search/folder
  components rather than reimplement discovery or create competing catalogs.

Keep persistence, audio collection, widget binding and gesture state out of a
further enlarged MainActivity. MainActivity composes focused controllers.
Validate document size, page/widget counts, IDs and dimensions with documented
limits before implementation. Migration is versioned/idempotent; retain last-good
state and show a recoverable error instead of wiping corrupt preferences.

## Delivery sequence

1. Use the confirmed vertical widget stack and visible direct-navigation rail.
   Finalize remaining default sections and category migration/filtering wording
   before any product code changes. The boundary transition replaces All apps'
   pull-down reachability; category-list reachability stays independent.
2. Implement navigation state machine and unified page model/migration. Deliver
   Home + All apps with existing rail/search/Most Used/folders and safe Back/HOME.
   Validate same-gesture scroll-to-boundary handoff, inverse transition, partial
   drag cancellation/reversal and rail pointer continuity before adding widgets.
3. Add screen management and built-in clock/date/battery sections. Verify small
   screens, font scaling, saved anchors and wallpaper/window transitions.
4. Implement native widget host lifecycle, picker and bind/configuration flows.
   Verify real providers early, especially scrollable/resizable widgets, before
   polishing edit/reorder/move interactions.
5. Add Listening apps using shared headset signals. Cover unavailable/unknown
   states and deferred changes during interaction; verify on physical hardware.
6. Run regression and visual acceptance against WS-A01–A10. Produce signed Core
   APKs using existing CI. Keep future external page rendering a separate slice.

## Focused work packages

- Navigation/state agent: owns page identity/migration and navigation reducers;
  covers origin restoration, HOME, Back, categories and persisted compatibility.
- Widget agent: owns host controller/AndroidView bridge and widget lifecycle tests;
  does not introduce new global catalogs, private signing access or page ordering.
- Home UI agent: owns workspace sections, screen organizer and gesture surfaces;
  consumes agreed state contracts and preserves rail/list functionality. Reuse
  the existing alphabet animation and coordinate vertical motion through one
  owner; do not create competing list overscroll and page-dismiss handlers.
- Context agent: owns Listening preferences/projection and shared audio subscriptions;
  no background scanning, automatic playback or restricted permission dependency.
- Validation agent: reviews integration, native screenshots and failure cases;
  coordinator owns MainActivity wiring, cross-agent contracts and final acceptance.

Six focused GPT-6 Sol agents implemented persistence, navigation, widgets,
settings, Listening apps and validation with disjoint file ownership. The root
coordinator owns MainActivity integration, review and final acceptance. Run
focused tests per slice, then the required CI suite once integration is stable.
