# App index and actions

This increment implements HOME-V2-03/04/08 on top of main `241914fb387c`.
Repowise and jCodemunch were checked against that baseline. Repowise's index is
partial and its test suggestions are inferred; CI is the validation authority.

## Implementation

1. Build the rail from actual locale sections, retaining the existing fixed
   touch lane and curved feedback. Short rails cap their slots at 24 dp.
2. Match search against displayed labels and locale-neutral package identifiers.
3. Keep a versioned, bounded, atomic app-index snapshot in no-backup storage.
   Validate profiles and locale, publish warm metadata, then reconcile live.
   Coalesce package events and replace only their package/profile entries.
   Full refresh remains for startup, profile/locale changes, manual retry and
   recovery; unchanged data does not advance the catalog generation.
4. Present app actions in a themed modal sheet. Android owns app details and
   uninstall confirmation. Profile identity is preserved in every action.
5. Persist rename/hide preferences separately from the raw catalog. Core's
   alphabetical presentation uses the visible labels; explicitly selected sort
   providers retain ordering authority. Restore controls use the raw list.

## Design review

Hallmark component scope: no page redesign or theme replacement. The sheet uses
the existing Material theme, native type scale, opaque surfaces, a clear app
heading, leading action icons, and 56 dp minimum action rows. Long names wrap in
the heading; the action list scrolls if it cannot fit. No categories, screen-time
controls, upsells, or unsupported advanced actions are shown. User wallpaper is
never copied or bundled. New UI lives in AppActionsSheet/AppCustomizationUi;
HomeApps, HomeAppList, HomeScreen and MainActivity provide integration. No file
deletions are required.

Pre-emit review targets (1–5): philosophy 4, hierarchy 4, execution 4,
specificity 5, restraint 5, variety 4. These are design judgments, not measured
usability outcomes. Screenshot fixtures cover 320, 375, 411, 414 and 768 dp,
including large font and light/dark states; inspect CI artifacts before merge.
Native pressed/focus feedback, unavailable launch state, catalog loading,
write errors and successful restore/name changes are covered by the component
behavior and tests rather than decorative status messages.

## Validation and device follow-up

- Unit tests: cache round trip/corruption/profile/locale checks, incremental
  replacement/removal, package search (including Turkish casing), preference
  identity and normalization.
- Device tests: warm cache publication and live reconciliation; populated
  Norwegian rail updates; package search; menu without a pin provider; disabled
  app management; rename/hide/search/restore/reset; preference persistence.
- Run existing Home navigation, wallpaper, framework, icon-cache and IPC suites.
  Retain repeated keyboard/search transition checks.
- On the user's Samsung device, check a cold start after reboot, install/update/
  removal while Core runs, and cancelling Android uninstall. Startup latency is
  not benchmarked by this change; do not attach a numerical speed claim.

Most Used remains optional. Core provides generic app-management/presentation
behavior; the add-on owns usage permission, ranking and saved pin semantics.
