# Home widget grid v2

Requested November 9, 2026. This extends [Home workspace v1](home-workspace-v1.md)
and supersedes the two-column row layout in [Widget layout](widget-layout.md).
Home remains a vertically scrolling workspace above the distinct All apps view;
the grid changes widget placement within Home and custom widget pages.

## Home and All apps content

Home contains its configured clock/date/battery, widgets and contextual Listening
section. Most Used and folder shortcuts live in the separate All apps view. Its
visible order is **Most Used → Folders → alphabetical apps**: Most Used appears
only while enabled, and the single Folders section appears only when it has
eligible shortcuts, regardless of the Most Used toggle. That section combines
visible folders once per identity: **Pinned** folders use their saved manual
order first, followed by **Alphabetical** folders sorted by name. These UI names
map to the existing `MOST_USED` and `APP_LIST` saved placement values. Saved pins,
folder placement and smart-visibility rules remain authoritative; presentation
does not move or duplicate stored data. There is no Show on Home folder option.
On Home, the star opens drawer Most Used and the diamond opens drawer Folders.
The star follows the Most Used enabled state, including its empty state; the
diamond appears only when at least one folder shortcut is eligible.

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

- Horizontal swipes on Home visit only configured Home/widget pages. Category
  pages belong to the separate drawer: horizontal swipes there move between
  All apps and enabled categories. Each surface keeps its own page order and
  selected page. Returning from the drawer restores the originating Home page.
  Home screen settings arrange widget pages; category settings arrange drawer
  pages around All apps. Reordering either surface preserves the other order.
- Home scrolls vertically through widget content. At rest, Home and All apps
  are strictly separate full-screen views: neither shows a strip of the other.
  When the Home scroll reaches its bottom, the remaining motion in that same
  upward drag opens central All apps from below at scroll position zero, with
  Most Used first when enabled, regardless of the previous category or letter.
  Remaining upward movement in that opening gesture is consumed; a new gesture
  scrolls the drawer. Letter taps still open their selected section.
  The reverse boundary handoff uses the
  same downward drag. Widget taps, scrolling and other provider gestures remain
  available outside edit mode.
- Long press empty Home space or a widget to open the app-action-style bottom
  sheet titled **Home screen**, with **Add widget**, **Edit** and
  **Advanced**. Edit enters the on-Home layout editor; Advanced opens the
  **Home grid** submenu directly. The settings route is Settings > Productivity
  > Home screens (page title **Home**) > Home grid. Back from Home grid returns
  Home. Choosing Edit after holding a widget selects that widget. Home has no
  permanent Add widget or Edit Home footer buttons.
- In edit mode, hold and drag the widget body between valid cells. Drag its
  physical bottom-right grip to resize column and row spans, keeping the top-left
  corner fixed, including in RTL. The grip is a minimal equal-sided corner mark
  (┘), with no arrow shaft or button background. Reject overlaps and sizes unsupported by the
  provider; show an error when a save fails. There are no move arrows or size
  increment buttons. The edit header exposes a **Remove widget** icon for the
  selected widget, alongside Done, keeping small cells clear. Remove widget asks for confirmation;
  cancellation keeps the widget. A successful removal persists the change and
  releases its owned Android widget binding. A failed save keeps the widget and
  shows a retryable error. The provider app stays installed. Home screens settings provide provider configuration,
  page moves, size/padding edits and Remove. Widget gestures outside edit mode
  remain available to the provider; a launcher hold opens the Home screen sheet.
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
