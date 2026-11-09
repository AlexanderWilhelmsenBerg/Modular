# Home widget grid v2

Requested November 9, 2026. This extends [Home workspace v1](home-workspace-v1.md)
and supersedes the two-column row layout in [Widget layout](widget-layout.md).
Home remains a vertically scrolling workspace above the distinct All apps view;
the grid changes widget placement within Home and custom widget pages.

## Layout and sizing

- The default grid has four columns and 96 dp rows. Users can set 1–12 columns
  and 32–256 dp row height. There is no fixed row count: the workspace grows
  vertically with its widgets.
- Widget size is expressed as columns × rows. A 4 × 4 widget occupies four
  columns and four rows; multiple 1 × 1 widgets can share one row. New widgets
  choose an initial span from Android's advertised target cell size when
  available and otherwise from its minimum size. The allocated content must
  respect the provider's minimum dimensions and supported resize directions.
  The native host view clamps content to the provider's advertised maximum
  width and height inside its allocated cell.
- Area width (50–100%), outer margin (0–48 dp), inter-cell gap (0–48 dp) and
  inner padding (0–32 dp) remain configurable. The alphabet rail retains its
  56 dp physical right-side reservation, including in RTL. A provider's own
  padding remains under provider control.
- Saved non-overlapping positions retain their cells. Automatic, invalid and
  conflicting placements use deterministic first-fit placement without
  dropping widgets. Changing column count reflows positions that no longer fit.
  Widget pages and Home use the same area settings but keep separate widgets.
- Existing version 1/2 saved widgets migrate through defaults: old Full spans
  use all columns; old Half spans use half the available columns rounded up.
  Their saved content height rounds up to enough grid rows without shrinking
  the hosted content. Their host identity, page, order and padding remain.

## Interaction

- Home scrolls vertically through widget content. At rest, Home and All apps
  are strictly separate full-screen views: neither shows a strip of the other.
  When the Home scroll reaches its bottom, the remaining motion in that same
  upward drag opens All apps from below. The reverse boundary handoff uses the
  same downward drag. Widget taps, scrolling and other provider gestures remain
  available outside edit mode.
- Long press empty Home space to open a menu with **Add widget**, **Edit** and
  **Advanced**. Edit enters the on-Home layout editor; Advanced opens the
  **Home grid** submenu directly. The settings route is Settings > Productivity
  > Home screens (page title **Home**) > Home grid. Back from Home grid returns
  Home. Long pressing a native widget selects it and enters the layout editor.
- In edit mode, select a widget, drag it between valid cells, or use its resize
  handle to change column and row spans. Reject overlaps and sizes unsupported
  by the provider; show an error when a save fails. On-Home controls provide
  Move, Resize and Done. Home screens settings provide provider configuration,
  page moves, size/padding edits and Remove. Empty-space long press and widget
  long press have distinct targets.
- Android widget binding, consent, configuration, host lifecycle and recovery
  continue to follow Home workspace v1. The launcher reports measured content
  size to Android after layout changes.

## Storage and limits

Grid column count, row height, widget span and optional origin are persisted
with the organization snapshot. New placements store positive spans; older
placements use zero spans to resolve their legacy size. Explicit row spans are
limited to 32 and saved row positions to 256. A legacy height may resolve to
more rows solely to preserve existing content. Placement is bounded and
rendered in vertical bands so a long Home does not require one enormous view.

Acceptance evidence and remaining device checks are recorded in
[Home widget grid v2 validation](../implementation/home-widget-grid-v2-validation.md).
