# Widget layout

Requested November 9, 2026. Extends [Home workspace](home-workspace-v1.md).

Settings > Productivity > Home screens controls the widget layout. Home and
custom widget pages share area settings; each widget keeps its own size.

- Area width: 50–100% of the space remaining after the alphabet rail and outer
  margins. Center narrower areas within that space.
- Outer margin: 0–48 dp on both sides of the available widget area.
- Widget spacing: 0–48 dp between cells and rows.
- Widget width: Full or Half. Pair consecutive eligible Half widgets without
  changing saved order. A lone Half retains its half-width slot; a Full widget
  starts its own row. A provider that cannot fit Half falls back to Full.
  Providers with an explicit maximum width keep that limit inside the slot.
- Widget height: adjustable within the provider's supported vertical range.
  Non-resizable providers keep their required content height.
- Inner padding: 0–32 dp around each hosted widget. This is launcher padding;
  backgrounds or spacing drawn by the provider remain provider-controlled.

At 100% area width, zero outer margin and zero inner padding, the widget spans
from the physical left content edge to the alphabet rail's reserved touch lane.
The 56 dp rail reservation remains on the physical right in RTL. System display
cutouts/insets still apply. No invisible launcher padding may remain in the host.
Zero spacing lets adjacent cells meet. Existing non-widget sections retain
their own spacing.

Each row's height is its tallest widget including inner padding; shorter widgets
align to its top. Height is the provider content height, excluding inner padding.
Actual content dimensions are reported to Android after layout, including after
rotation, resizing and padding changes. Controls explain minimum-size fallback
instead of clipping a wide provider into a half slot.

Changes are explicit saves, with errors retaining the draft. Existing saved
widgets retain identity, page, order and height, defaulting to Full and zero
inner padding. Old version 1/2 organization data remains readable. Area defaults
are 100% width, 16 dp margin and 12 dp spacing.

Acceptance requires packing/geometry/persistence tests, real bound-widget
measurements and recreation, and captures at narrow/wide sizes, enlarged fonts
and RTL. Verify zero-margin bounds, half-width pairing, narrow fallback, actual
Android size options and all existing widget configuration/navigation flows.
