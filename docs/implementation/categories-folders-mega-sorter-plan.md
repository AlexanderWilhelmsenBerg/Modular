# Categories, folders, and Mega Sorter implementation plan

Status: **Implementation authorized and integrated in the feature branch; acceptance in progress.**
Date: 2026-11-08. Product contract:
[Categories, popup folders, and Mega Sorter](../product/categories-folders-mega-sorter.md).
Current evidence and device checks: [acceptance record](category-validation.md).

## 1. Verified baseline and impact

Authoritative GitHub main at inspection:
`b1b133b3723dd092e4ab083f22ade975098facc8`.
Repowise Modular reported matching indexed/live head `b1b133b3723d`, no index
lag, and a **partial** index. jCodemunch identified the current Home/Most Used
symbols; direct local source inspection checked their behavior. The workspace
has no `.git` directory; revision evidence comes from GitHub, not a local Git
status. This is an architectural planning baseline, not a review of implemented
category code or a new CI clearance.

Current integration points, under
`app/src/main/java/io/github/alexanderwilhelmsenberg/modular/`:

| Existing area | Required integration |
| --- | --- |
| `apps/AppModels.kt`, `AndroidAppRepository.kt`, app-index snapshot/cache | Preserve exact AppKey; add/cache nullable Android category metadata; scoped package refresh |
| `apps/AppCustomizationStore.kt` | Preserve explicit rename/Hide behavior; apply it before all organization projections |
| `ui/HomeScreen.kt`, `HomeAppList.kt`, `HomeSectionBuilder.kt` | Separate global catalog/search from per-page lists; typed folder entries; populated per-page rail; stable scroll anchors |
| `ui/HomeApps.kt`, `AppActionsSheet.kt` | Shared organization picker and exact-app actions; independent sorter row interaction |
| `mostused/MostUsedSettingsStore.kt`, `BuiltInMostUsedController.kt` | Typed app/folder pin references, safe legacy app-pin migration, Home visibility projection, shared launch learning |
| `MainActivity.kt`, `ui/ModularApp.kt` | Compose stores/controllers/page host/settings slots; retain HOME/Back intent behavior |
| Existing appearance/theme code | Reuse theme/window/wallpaper behavior; central sorter feedback tokens; no transparent-window workaround |

Repowise marked MainActivity, HomeScreen and HomeApps as churn hotspots. Its
graph and historical co-change recommendations are advisory. Keep new behavior
in focused models/controllers/composables instead of extending MainActivity's
large onCreate or embedding persistence and gesture policy in HomeScreen.
No module API/AIDL change is required for this Core feature set.

## 2. State and ownership design

Introduce focused logical models (names provisional):

- `CategoryId`, `FolderId`, `CategoryDefinition`, `FolderDefinition`.
- `MembershipOverride` = Auto / Include / Exclude, keyed by CategoryId + AppKey.
- `HomePageId` = Home / Category(id); one saved page order, independent of enable flags.
- `HomePinRef` = App(AppKey) / Folder(FolderId), preserving manual order.
- `OrganizationSnapshot`: definitions, overrides, folder members, placements and revision.
- `OrganizationProjection`: global searchable apps, direct Home apps, category
  pages, resolved folders and visible starred entries.
- `FolderVisibilityPolicy`: Always show / Smart / Manually hide, flat condition
  group with Any (default) / All and Unknown fallback. Keep contents independent.
- `FolderCondition`, `SavedPlace`, and `ConditionResult`: headset, schedule,
  connected Wi-Fi, map area; tri-state result with reason and freshness.
- `FolderConditionCoordinator`: one shared lifecycle-aware signal collector,
  pure rule evaluator, bounded location refresh and deferred UI projection.
- `MegaSorterSession`: target IDs, stable row identities/order, per-row pending
  and visual state, and bounded undo records conditional on the affected
  app/category pair's current override and operation token/version.

Keep raw package discovery authoritative; do not turn categories into fake
Android profiles or existing add-on LauncherContexts. A folder is a launcher
collection, not an installed app or filesystem path. Derived membership and
counts are computed from current metadata plus overrides, never saved as a
second independently mutable source of truth.

Use one shared transactional Core preference backing for organization edits and
the app/folder pin references they affect. Factor singleton DataStore access
behind logical organization and Most Used stores; do not instantiate competing
DataStores on the same file. Reuse/migrate the existing settings payload safely
so folder creation + initial members + placement can commit atomically, and
folder deletion cannot leave an active shortcut to a missing folder. Local
learning history stays separate and keeps its current backup exclusions.
The organization picker's complete Done batch, including staged creations and
category/folder membership changes, is one transaction; Cancel discards all
staged edits. Concurrent destination deletion must produce a recoverable
validation error, never partial success.

Existing app-only pins migrate to `HomePinRef.App` in exactly the same order,
with identity, toggle, source, history window, count and legacy-import records
preserved. Migration is versioned/idempotent and keeps a recoverable prior
snapshot until validation succeeds. Review effects on legacy Most Used import
and every pin-writing entry point; do not maintain two competing pin orders.

Validate IDs, references, names, duplicate order entries, enum values, file size,
collection bounds and schema version. Report failure with last-good state; do
not erase settings to recover from malformed data. Define and test sensible
documented bounds before implementation review, including the existing shared
50-pin limit. Version disposable category metadata caches separately from durable
manual choices. No new backup inclusion for learned usage is permitted.

The C1 organization store enforces these bounds: 2 MiB encoded organization
document; 64 total categories including built-ins; 128 folders; 2,000 ordered
members per folder; 20,000 distinct app/category membership pairs; 60 Unicode
code points per category or folder name; 128 characters per ID; 16 condition
references per folder; and 50 shared app/folder pins. Names are trimmed,
nonblank, and unique within their own category or folder namespace using the
device locale's primary-strength collation. Invalid edits fail atomically with
an error; malformed stored data retains the last validated snapshot.
Sensitive saved places and network names use immutable versioned records in
private `noBackupFilesDir` storage; the transactional organization document holds
opaque references. Do not claim atomic DataStore commits across two files. Stage
and durably write a complete sensitive record before atomically replacing its
reference in the organization transaction. Failed/canceled outer saves leave old
references unchanged; unreferenced staged records are invisible and reclaimed
on recovery. Shared-place edits update all intended references in that single
transaction; keep prior records until no committed reference uses them. Reconcile
at startup, with deterministic cleanup/retry after interruption. No raw SSID or
coordinates may leak into the backed-up organization document or display names
generated from sensitive values. On restore, missing sensitive definitions
produce Unknown/reconfigure, never a fabricated matching place.
Live fixes and condition history are ephemeral, not an organization audit trail.
Folder visibility does not rewrite typed pin storage on every signal change.

## 3. Delivery slices and agent boundaries

Each slice should have one owner and an independent read-only review. Parallel
work is safe only after shared models/interfaces are agreed; avoid concurrent
edits to MainActivity or the common state store. These are future implementation
assignments, not claims that agents have implemented them in this draft.

### C1 — Models, store, metadata, and projections

Owner: organization/data agent. Suggested new files under `organization/`:
`OrganizationModels.kt`, `OrganizationStore.kt`, `CategoryClassifier.kt`,
`OrganizationProjection.kt`; factor shared settings DataStore ownership as needed.

- Implement supported Android metadata mapping and cache refresh, with unknown
  values left unassigned. Do not add Usage Access, broad package permissions or
  online package lookup.
- Implement Include/Exclude/Auto, CRUD, page order, folder membership/placement,
  transactional commands, revisions, and typed-pin migration.
- Implement one visibility projection used by all surfaces, including the
  any-hide-wins rule, preserved-but-suppressed pins, explicit Hide, unavailable
  references and global search input. Filter external featured rows at the Core
  presentation boundary too, without changing provider output or protocol.
  Filter built-in suggestion eligibility before its count limit.
- Unit tests: mapping/fallback/unknown; override truth table; multi-category
  visibility; malformed state; migration/restart; concurrent updates; failed save
  retains last-good state; create/delete invariants.

Gate: ORG-A01–A05 state behavior and ORG-A09 migration cases. UI owners receive
stable, immutable view state and suspend commands returning explicit results.

### C2 — Category settings and page host

Owner: category UI agent. Suggested `ui/CategorySettings.kt`,
`ui/CategoryPageOrder.kt`, `ui/LauncherPages.kt` plus narrowly scoped integration.

- Build master/per-category/Show on Home/auto switches, custom-category editor,
  provenance/member controls and compact reorder tiles with non-drag alternatives.
- Add keyed page state around the existing app list, without duplicating app
  discovery, search, launch, icon-loading or Most Used services for each page.
- Explicitly arbitrate vertical scrolling/reachability, alphabet rail, global
  search, IME, system-edge Back and horizontal paging.
- Handle empty/disabled/deleted current pages, Settings return and HOME reset.

Gate: ORG-A01, A04–A07; no changes to default Home when categories are off.

### C3 — Folders and organization action sheet

Owner: folder/action UI agent; can run alongside C2 once C1 APIs are stable.
Suggested `ui/FolderPopup.kt`, `ui/FolderEditor.kt`,
`ui/AppOrganizationPicker.kt` and typed HomeListEntry additions.

- Render the app-list Folders section/rail destination and typed starred folder
  shortcuts. Folder content remains independently accessible in the catalog.
- Implement anchored, bounded popup, member ordering, dismissal/focus, empty and
  unavailable states, and a single active popup/sheet stack.
- Extend existing app action sheet with Add to category and the category/folder
  chooser, including transactional create-with-current-app and Cancel/Retry.
- Route launches through the same Core learning hook from every new surface.

Gate: ORG-A05, A08–A10, A16; physical wallpaper scrolling test verifies no ghosting.

### C3b — Smart folder visibility and per-folder settings

Owner: condition/state agent plus folder UI owner at their agreed interface;
depends on C1 models and C3 folder management. No edits to existing module IPC.

- Implement pure tri-state Any/All evaluation, modes, per-folder fallback and
  status reasons. Manage folders always renders saved definitions independently
  of shortcut eligibility; preview/edit remains available for hidden folders.
- Add scoped AudioManager/connectivity/time signal adapters and foreground
  location abstraction. No mandatory GMS dependency, active Wi-Fi scans,
  background service, or exact-alarm permission. Named Bluetooth device and
  charging conditions remain recommendations outside the committed slice.
- Share listeners/requests across active rules, enforce freshness/timeout/rate
  bounds, unregister/cancel when not visible, and re-evaluate on resume/recreation.
  Handle approximate fixes, boundary uncertainty, missing providers, permission
  revocation, and request failure as explicit state, not exceptions escaping UI.
- Build map-area editor with proposed circle/radius controls, local named areas,
  numeric fallback, permission education and optional current-location action.
  Select compatible renderer and tile service, attribution/cache policy and
  production quota before implementation. No online app-metadata API is selected
  by this slice. Saved areas evaluate without downloading map tiles each time.
- Derive shortcut eligibility without altering contents or pin positions; defer
  removal under active interaction/open popup and preserve list anchors.
- Unit tests use fake clock/audio/network/location adapters and permission
  states. Test Any/All Unknown truth tables, overnight/DST ranges, map boundary
  accuracy, stable IDs, restore with missing sensitive data and request budgets.
  UI tests cover settings access for every hidden state and popup transitions.

Gate: ORG-A17–A22. Physical battery/permission validation is required; document
measured requests and behavior rather than promising zero power consumption.
Foreground-only evaluation, circle shape and Unknown fallback remain proposed
defaults; Any as default, Wi-Fi/map support and low-resource intent are confirmed.

### C4 — Mega Sorter controller, gestures, and animation

Owner: sorter agent; depends on C1 commands and C2 gesture ownership.
Suggested `organization/MegaSorterController.kt`, `ui/MegaSorterSetup.kt`,
`ui/MegaSorterRow.kt` and a scoped mode overlay in the page host.

- Explicit one/two-target setup and physical blue/right, green/left mapping.
- Stable full-catalog sorting canvas independent of Home filtering and duplicate
  starred/folder entries. Disable parent paging in the mode.
- Per-AppKey state machine: idle → dragging → pending/outgoing → confirmed return
  → assigned, with canceled/error recovery. Animation is never a data commit.
- Keep measured row slots unchanged; animate draw/translation/clip/alpha only.
  Avoid global busy flags and list-index keys. Permit a second app swipe and
  vertical scrolling while prior animations/writes are active.
- Undo restores the previous override with an expected-value/operation guard for
  that app/category pair. Unrelated writes must not invalidate the inverse.
  Deleted destinations invalidate their side immediately, and queued writes
  revalidate at commit without resurrecting deleted definitions. Exit
  retains accepted commands, cancels visual work safely, and restores normal
  projections. Reduced motion and tap-to-assign use the same store commands.

Gate: ORG-A11–A14 plus automated gesture tests controlling the animation clock.
Inject slow storage and scroll the first row offscreen while assigning another.
Assert stable slot bounds and exact membership; a screenshot alone is insufficient.

### C5 — Integration, visual review, and release evidence

Owner: coordinator/integration agent; separate review agent checks the diff.

- Run appropriate unit tests, lint, connected tests and existing search/rail,
  customization, Most Used, migration and external-module regressions.
- Capture actual native screens for all category/folder/sorter states at the
  specified widths/themes/font scales. Record sorter video showing overlapping
  animations and vertical scrolling. Do not infer performance from still images.
- Verify cold/warm HOME, activity/process recreation, uninstall/update, RTL,
  system Back edges, reduced motion, and save failure/undo.
- Check a representative large installed-app catalog. Document device/catalog
  size and observed frame behavior instead of asserting an unmeasured frame-rate
  guarantee. Classification must not requery Android on every list frame.
- Attach exact commit/run/artifact evidence to ORG-A01–A22. Physical Samsung
  acceptance remains explicit; automated tests cannot stand in for it.
- Keep signing identities and existing package IDs. No Google submission is
  performed during specification work. After the app is finished, investigate
  any remaining Play Protect harmful-app warning against the exact final APK
  and prepare the user-authorized Google review/appeal; ordinary Android
  restricted settings are a separate mechanism.

## 4. Validation and release gate

The user authorized implementation on 2026-11-08. C1, C2, C3, C3b and C4 have
implementations and targeted tests in this branch. C5 requires local builds/lint,
CI emulator regression and screenshot evidence, plus physical Samsung testing
for location/headset/Wi-Fi, battery and gesture behavior. Record actual results
in the acceptance record. Do not infer physical-device acceptance from a compile
or a documentation check. Online metadata providers, background geofencing and
the optional recommendations remain outside the implementation scope.
