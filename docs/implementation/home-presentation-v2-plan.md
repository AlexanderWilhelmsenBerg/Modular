# Home presentation v2 implementation plan

Status: **Source implemented; acceptance tracked in PR #19 and the acceptance matrix.** Baseline: main
`3e5559f552d9218c23b67b3b45e73d1f3113839d` (PR #17).
Requirements: [Home presentation v2](../product/home-presentation-v2.md).

## 1. Source and design review

GitHub main, jCodemunch's Modular index, and Repowise Modular all identify the
same baseline (Repowise reports `3e5559f552d9`, not behind). Repowise's index
scope is partial and its coverage map is unavailable; its dependency/test
suggestions are planning aids, not proof that tests were run. Authoritative
source was checked through GitHub for HomeScreen, HomeSortController,
AppSortResultParcel, and MostUsedSortService.

Confirmed implementation constraints:

- `ui/HomeScreen.kt` currently combines top controls, search, list, and recovery
  UI. Split responsibility incrementally; preserve navigation/recovery handlers.
- `home/HomeSortController.kt` exposes one ordered list and requires a permutation
  of all input keys. That validation must stay intact for v1 sort providers.
- `module-api/.../AppSortResultParcel.kt` has a fixed positional parcel layout
  (status, orderedKeys, message). Do not append section data to this ABI.
- `addon-most-used/.../MostUsedSortService.kt` returns a whole-list ranking and
  rejects calls without Usage Access. Featured pins need a separate success path
  that works when usage ranking is unavailable.
- `apps/AppModels.kt` already has exact package/activity/profile identity.
  Obtain icons through a separate cache/repository; do not send bitmap payloads
  through the add-on contract or weaken identity for convenient lookups.
- Protocol v4 already supports capability negotiation and per-instance settings.
  Add a separately versioned capability and SDK API; a global protocol bump is
  not justified merely by introducing a new capability ID.

Hallmark reference study: left-aligned icon/name rows, sparse section letters,
wallpaper with dimming, a right-hand rail that curves during drag, and a selected
letter bubble. Native Android tokens and supplied screenshots govern this work;
web hero/footer templates and CSS exports are not relevant. Typography and motion
values are calibration targets, not recovered proprietary font/timing data.

## 2. Delivery sequence and ownership

These are bounded implementation work packages. Each package must land with its
relevant acceptance evidence; do not mark the whole revision complete after the
static UI alone. They can be delegated separately after the shared models and
interfaces are settled.

### P1 — Core appearance, icons, sections, and bottom controls

Owner: Core presentation implementer. Dependencies: none.

- Extend persisted appearance preferences with background mode, dimming, and
  show-icons. Existing themes remain unchanged; absent new preferences use the
  documented defaults. Add normal controls to Appearance settings.
- Add an asynchronous, bounded icon repository keyed by exact AppKey, density,
  size, and relevant configuration. Invalidate on package/profile changes and
  avoid decoding while composing or scrolling. Prefer LauncherApps platform
  icon APIs; cached failures use placeholders and can recover after an update.
- Build a pure `HomeSectionBuilder` over current catalog objects and locale
  buckets. Use stable keys for headers and rows and preserve duplicate-label
  identities. Use Android ICU alphabetic indexing where appropriate, verified
  with Norwegian and non-Latin fixtures; do not group by the first UTF-16 char.
- Extract HomeAppList and HomeBottomControls from HomeScreen in place. Add icon
  slots/letter headers, move search and gear to the bottom, and keep all loading,
  empty, error, disabled-profile, launch, and HOME/Back behavior.
- Add the optional transparent wallpaper layer using Android's wallpaper window
  support, scrim, and themed fallback. Keep Settings opaque; restore window
  flags when entering/leaving Home. Honor live wallpaper and configuration changes.

Expected touch points: MainActivity, appearance store/theme/settings UI,
HomeScreen, HomeAppRow, HomeApps, AndroidAppRepository integration; additive
AppIconRepository, HomeSectionBuilder, HomeAppList, HomeBottomControls, and
HomeBackground components. No production-file deletion is required.

Gate: V2-A1/A3/A6, app completeness and icon-cache tests, baseline navigation
regression tests. This stage works without any add-on or new IPC contract.

### P2 — Alphabet rail and retained pull-down reachability

Owner: Core gesture implementer. Dependency: P1 section model and row geometry.

- Introduce an isolated AlphabetRail with a stable absolute-position hit map,
  locale buckets, star-slot support, empty-letter resolution, direct letter taps
  even on compact rails, and an explicitly invoked accessibility section chooser.
- Animate rail transforms and the selected-letter bubble from pointer position.
  Bound displacement; cancel stale scroll work. Use immediate tracking while
  scrubbing and a short settle on release; honor animation scale/reduced motion.
- Add a HomeReachabilityState and nested-scroll handling. Retain released leading
  space until upward movement or an explicit reset, capped at the first-row
  midpoint. This must be real interactive layout space, with matching hit testing,
  not a visual translation that leaves app touch targets behind.
- Coordinate list/rail/search gestures through one navigation state. Bottom
  controls and wallpaper stay fixed. Persist ordinary return/recreation anchors
  separately from cold-start/HOME resets. Handle catalog changes by AppKey/header
  anchor rather than stale numeric row indexes.

Gate: V2-A2/A4/A5/A6, a video of rail scrubbing and reachability on a physical
phone, and deterministic gesture/state tests. Review the settled and selected-K
states against the supplied screenshots at equivalent viewport/font settings.

### P3 — Bounded featured-app capability and Core host

Owner: API/SDK implementer. Can begin alongside P1; integrate after P1 models.

Proposed capability ID: `modular.apps.featured/v1`. Finalize and document its
AIDL/parcel definitions before either side consumes them:

- Read request: current bounded catalog items, locale, opaque catalog revision,
  and request identity. Instance/configuration identity comes from the acquired
  v4 session. Reuse established catalog bounds/paging patterns; exceeding an IPC
  limit must never truncate Core's visible catalog.
- Read result: echoed request/catalog identity, feature revision, ordered pinned
  keys, ordered suggested keys, and a bounded typed status (ready, permission
  required, no history). No arbitrary view tree, app-label replacement, intent,
  bitmap, or raw usage metrics. Core maps every actionable key to its own catalog. Saved pin keys absent from
  the catalog are unavailable management entries only; unknown suggestion keys
  invalidate the contribution.
- Mutations: set pinned/unpinned and replace pin order, scoped to the active
  instance. Bound lists to 50; adding a pin requires a current catalog identity, while
  removing/reordering may include already-saved unavailable pins. A reorder must
  be a unique permutation of the saved pins. Use an expected
  feature revision to detect stale edits, and return the committed revision.
  Retry set-state operations safely; do not implement a non-idempotent toggle.
- Core rereads after acknowledged mutations, return from the management Activity,
  catalog refresh, or instance/route changes. A push subscription is unnecessary
  in this slice. Ignore any late result from an older request, session, or catalog.
- A generic HomeFeaturedController validates output and emits optional section
  state. Keep HomeSortController's v1 path separate. No simultaneous active
  featured mode and whole-list custom-sort mode; ordinary selection changes the
  relevant bindings atomically and preserves unrelated context bindings.
- Pin controls are generic Core-rendered app picker/list/context actions attached
  to this capability. Most Used stores pins. Core need not acquire Usage Access,
  read raw statistics, or request a global add-on app-catalog grant to render its
  own picker. The add-on sees only the bounded catalog/identities needed by the
  explicitly selected feature, under the same session trust boundary as sorting.
- Validate management Activity links through the existing descriptor path.
  Never accept provider-supplied navigation intents. Report privacy-safe failure
  categories through the existing module event history.

Expected touch points: module-api, module-sdk, StandardCapabilities, routing and
selection/controller integration, ModuleSettingsController/ModuleSettingsUi,
MainActivity; additive featured parcels/interfaces/validators/controller and
generic pin-editor UI. Update the standalone SDK consumer and compatibility docs.

Gate: V2-A9 plus codec/bounds/authentication/revocation/stale-result tests,
separately built provider proof, and unchanged v1 sort cross-APK tests. A malformed
featured response never changes alphabetical app availability or launch metadata.

### P4 — Most Used starred section, pins, and migration

Owner: Most Used implementer. Dependency: P3 contract and Core host.

- Implement the featured capability alongside existing v1 sorting. Persist an
  ordered pin list per instance in add-on-private storage, with schema versioning
  and atomic writes. Bound malformed/corrupt data; show repair errors without
  presenting fabricated rankings or silently discarding the user's saved pins.
- Reuse the tested foreground-time query/sorter for unpinned suggestions. Query
  under the add-on's Binder identity only after authenticating Core. Permissions
  affect suggestions, not manual pins. Do not persist usage history or export it.
- Add the bounded suggested-count setting (default 8, range 0–20), preserve days,
  and expose Pinned apps through the ordinary module Settings capability UI.
  Keep Usage Access/status and Configure in Modular visible in the native Activity.
- Integrate long-press pin/unpin, reorder/remove and Add app controls. Persist
  before acknowledging success; on failure retain the prior visible order.
- Migrate a compatible active Most Used selection to featured mode once, retaining
  the existing instance identity/day settings so pin ownership is stable. Use a
  managed Home context without editing an original advanced context in place.
  Migration is atomic/idempotent; unsupported or failed migration preserves the
  usable legacy choice and offers a clear upgrade/retry path in module Settings.
- Unavailable pins remain manageable but not actionable. No history/denied access
  keeps a useful star destination with manual pin and permission actions. Missing
  or failed provider removes the contribution and leaves complete Core Home.

Gate: V2-A7/A8/A9 on real separate APKs, positive usage ranking, pin persistence
and ordering, denied/revoked access, uninstall/reinstall, exact-profile identity,
and old/new Core/add-on combinations. No manual JSON/context setup in the test flow.

### P5 — Integrated review and delivery

Owner: integration/review coordinator. Dependencies: P1–P4.

- Add a v2 acceptance matrix to Core completion without rewriting historical
  baseline results. Mark each row with actual revision, device/API, and evidence.
- Update setup/SDK guides, diagnostics and migration instructions to the final
  implementation. Keep the distinction between a full alphabetical catalog and
  featured duplicates explicit.
- Run targeted unit/Compose/cross-APK tests, lint, independent SDK build and signed
  debug/release verification. Capture 320/375/414/768 dp, larger fonts, all Core
  themes, wallpaper variants, resting/dragging rail, and IME/reachability states.
- Review recordings for rapid rail scrubbing and physical Samsung gestures. Use
  1,000-app fixtures to check icon/scroll behavior and bound memory/work queues.
  Existing tests remain regression gates; earlier green CI is not v2 evidence.
- Publish matching Core and add-on APKs only after the applicable checks pass.
  Merge implementation after successful CI under the existing delivery policy.

## 3. Risks and planned responses

| Risk | Planned response |
| --- | --- |
| Alphabet navigation conflicts with whole-list usage ranking | Separate starred subset from the full alphabetical catalog; keep legacy flat-sort mode explicit. |
| Misleading interpretation of still screenshots | Record estimated geometry and proposed timing; verify with screenshots and gesture video before accepting visual parity. |
| Nested scrolling creates accidental launches or jumpy rail selection | One gesture coordinator, fixed hit map, cancelled stale work, real layout offset, and pointer cancellation tests. |
| Wallpaper/system bars differ by OEM | Use platform wallpaper window support, opaque Settings, safe fallback, and physical-device acceptance. |
| Pin writes race with configuration/profile/package changes | Exact AppKeys, per-instance revision checks, transactional writes, unavailable-state handling, no optimistic false success. |
| ABI breaks independent APKs | Add a new capability/interface, preserve v1 parcel bytes, negotiate support, and test old/new pairings. |
| Core loses apps while applying an add-on subset | Build the alphabetical catalog independently; validate optional contributed keys and discard only the invalid contribution. |

## 4. Current implementation map

The source tree contains implementations for P1–P4. These are code references,
not claims that the acceptance gates have passed:

| Package | Main source | Focused coverage and remaining gate |
| --- | --- | --- |
| P1 | `appearance/AppearanceSettingsStore.kt`, `appearance/HomeAppearanceControls.kt`, `appearance/WallpaperHomeScrim.kt`, `apps/AppIconRepository.kt`, `ui/HomeSectionBuilder.kt`, `ui/HomeBottomControls.kt` | `HomeAppearanceSettingsTest`, `AppIconRepositoryTest`; V2-A1/A3/A6 device and CI evidence pending. |
| P2 | `ui/AlphabetRail.kt`, `ui/HomeReachability.kt`, `ui/HomeScreen.kt`, `ui/HomeAppList.kt` | Gesture/locale tests and reference screenshots/video pending V2-A2/A4/A5. |
| P3 | `module-api` featured parcels/AIDL and `FeaturedAppsContract.kt`; `module-sdk/FeaturedAppsEndpoint.kt`; Core `capabilities/FeaturedAppsClient.kt`, `home/HomeFeaturedController.kt`, `ui/FeaturedAppSettingsUi.kt`, `composition/ModuleSettingsController.kt` | `HomeFeaturedControllerTest`, `FeaturedAppsCrossApkTest`; independent APK, malformed/stale/revocation and CI evidence pending V2-A9. |
| P4 | `addon-most-used/MostUsedSortService.kt`, `MostUsedSorter.kt`, `PinState.kt`, `PinStore.kt`, descriptor and native Usage Access Activity | `PinStateTest`, `MostUsedSorterTest`, `PinStoreTest`; real Usage Access, persistence, pin UI and old/new APK evidence pending V2-A7/A8. |

P5 remains open. Record the exact revision, device/API level, test results,
screenshots and video in the [v2 acceptance matrix](core-completion.md#home-presentation-v2-implementation-and-acceptance)
before calling this revision delivered. Physical Samsung gesture and wallpaper
checks remain especially important.
