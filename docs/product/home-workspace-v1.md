<!-- Hallmark · native reference-led specification · P4 H4 E4 S5 R5 V4 · draft only; no rendered implementation claim -->
# Home workspace v1

Status: **Implementation authorized and in progress; acceptance pending.** Request: 2026-11-08.
Baseline: settings/identity PR #28, merged as `fff68d16de7aa615f9725cefd48b7c8c9ff1cf7b`.

This draft adds a configurable landing Home above the existing app list. It
builds on [Home v2](home-presentation-v2.md),
[organization](categories-folders-mega-sorter.md), and Core Most Used.
The later [Home widget grid v2](home-widget-grid-v2.md) supersedes this version's
stacked widget-row placement and widget editing details. Home-to-All apps
navigation, widget host lifecycle and page behavior remain specified here.
The later drawer-order decision also supersedes this version's Most Used and
folder placement: see [Home widget grid v2](home-widget-grid-v2.md#home-and-all-apps-content).
Release evidence is tracked in the [acceptance record](../implementation/home-workspace-validation.md).
Requirements explicitly
requested by the user are distinguished from proposed defaults below.
See the [implementation plan](../implementation/home-workspace-v1-plan.md).

## 1. Requested scope and confirmed interaction decisions

Confirmed requests:

- A landing Home sits above the existing app-list screen; swipe up opens the list.
- Navigation offers direct access to an alphabetical section of the list.
- Users can add Android widgets to Home.
- Users can add screens on either side; later add-ons may supply more screens.
- Users choose music apps that appear when a headset is connected.

Confirmed by the user's follow-up:

1. Widgets use a **vertical stack of rows**, not a free-positioned grid. The
   November 9 layout extension allows two half-width widgets in one row.
2. The alphabet remains visible. Tapping a letter opens All apps at that letter,
   using the same navigation animation as the existing alphabet rail.
3. An upward swipe scrolls the Home stack first. At its bottom, continued upward
   movement transitions into All apps. Downward swiping in All apps returns Home.
4. The spatial relationship is consistent: **Home above, All apps below**.
   All apps enters from the bottom; Home returns from the top.

Boundary handling, cancellation thresholds and defaults not explicitly selected
by the user remain implementation proposals specified below. In particular,
downward motion first scrolls All apps to its top before returning Home, so the
user can still browse earlier apps normally.

## 2. Screen model and navigation — WORKSPACE-01

Use distinct product names:

- **Home:** permanent default landing page with user-selected content.
- **All apps:** existing alphabetical app list, presented below the workspace.
  Existing category visibility rules may omit direct entries; search still finds
  all non-hidden launchable apps. The name does not change that policy.
- **Workspace pages:** Home plus ordered custom widget pages.
- **Drawer pages:** All apps plus ordered category pages.
- **Add-on pages:** a future page type, unavailable until a versioned rendering
  contract is implemented and validated.

There are two separate horizontal sequences. For example, Home uses
`Notes widgets | Home | Music widgets`, and the drawer uses
`Work category | All apps | Games category`. Horizontal Home swipes never
open a category. All Home pages share the drawer; returning restores the
originating Home page. Settings arrange the two sequences independently.

| Action | Proposed result |
| --- | --- |
| Cold launch, app-icon launch, Android HOME | Main Home; close transient UI and reset search |
| Swipe up through the Home stack | Scroll content first; at its bottom, continued drag brings All apps up from below |
| Swipe down through All apps | Scroll toward the first entry; at its top, continued drag brings Home down from above |
| Tap Apps control | Open All apps directly, providing a non-gesture route |
| Tap a letter on Home/widget page | Open All apps and align that populated letter's header |
| Drag along the rail | Open All apps and continue the existing continuous letter selection |
| Swipe horizontally on a workspace page | Adjacent configured page; no wrapping at ends |
| Swipe horizontally in the drawer | Adjacent category/All apps page; never a widget page |
| Back in All apps, after dismissing search/popups | Return to the originating workspace page |
| Back on a side page | Return to main Home |
| Back on main Home | Leave Home visible |
| Return normally from a launched app or Settings | Restore prior screen and valid scroll anchor |

When an origin page has been deleted/disabled, return to main Home. A fresh
Android HOME intent overrides restoration and returns to main Home. Opening All
apps by the boundary swipe starts at the list's top, preserving the continuous
vertical relationship. A direct Apps action may restore its last valid anchor;
an explicit letter request always wins. Returning Home by downward boundary drag
restores the originating stack at its bottom; continued downward movement can
then scroll it toward its top. Back restores the origin's saved anchor, while a
fresh Android HOME intent shows main Home at its top.

Proposed bottom controls: persistent search and settings gear, with a compact
Apps action on workspace pages. Search enters the existing global search view
and preserves the origin; clearing/Back restores it. Search continues to match
display label and package identifier. No expanding search bar returns.

The right-side alphabet stays visible on Home/widget pages during ordinary
browsing, independently of stack scrolling. Existing search/edit/modal states
may temporarily suppress it to avoid competing interactions. There is no extra
button or menu required to reveal the alphabet.
Home/widget-page rail letters derive from the actual All apps projection, with
only populated locale buckets. Category-page letters continue to navigate that
category's own list. Use explicit page identity/title to keep this scope clear.
When Most Used is enabled, the star on Home opens its section at the top of All
apps. The diamond opens the drawer's single Folders section when populated.
No empty letter or folder targets.

Reuse the regular rail's selection feedback, inward curve, selected-letter
bubble, continuous drag targeting and release/settle behavior. A tap opens All
apps directly at the requested populated section; do not visibly scroll through
every preceding app. All apps still enters from below during this shortcut.
For press-and-slide, keep the same pointer session and fixed hit mapping across
the screen transition; do not require a second touch or queue obsolete targets.

## 3. Home content and visual direction — WORKSPACE-02

Reference: the supplied Niagara screenshot, 25724. Adopt the open wallpaper
space, clear clock/date hierarchy, left-aligned app content and right-side rail.
Retain Modular's themes, typography, icons and settings language. The screenshot
does not specify exact font identity or animation behavior. Its wallpaper and
promotional/setup cards are not assets or required Home content.

Proposed initial Home (updated by the later drawer-order decision):

1. Optional built-in clock/date block; battery percentage separately toggleable.
2. User-added widgets, in saved order.
3. A contextual Listening section when enabled and a headset is connected.

Users can reorder Home's clock, widget and Listening sections. Most Used and
folders are absent from Home. All apps presents Most Used first (when enabled), one Folders section
containing all visible shortcuts from both saved placements even when Most Used
is off, then alphabetical apps. Pinned folders retain manual order before
alphabetically sorted folders. A folder's saved placement, pins, order and smart
visibility remain intact; the display change does not silently move or duplicate
its stored shortcut.

Most Used retains its toggle, pins, order, source and local learning. The
alphabetical list still contains eligible individual app entries. Disabling
Most Used retains its saved state and does not hide visible folder shortcuts.

No sample apps, fabricated weather/calendar content, account requirement, or
automatic permission prompt. A fresh Home remains useful with clock/date and
clear Apps/search/settings access even without widgets or Most Used.

Wallpaper stays optional and stationary, shared across workspace and app list.
Light/Dark/AMOLED remain supported. Settings and editors remain opaque. Preserve
safe insets, wallpaper dimming and readable control backgrounds. Widget providers
may render their own backgrounds/colors; do not promise to recolor third-party
widgets or crop them into decorative cards that obscure their content.

## 4. Gestures and scrolling — WORKSPACE-03

- A launcher-owned upward drag, including one starting near the bottom above
  the system gesture inset, scrolls the Home stack first. It does not skip
  remaining Home content. Once the stack reaches its bottom, remaining drag
  distance drives the transition into All apps in the same gesture; no second
  swipe is required. A short/non-scrollable stack is already at that boundary.
- In All apps, downward drag scrolls toward its first entry first. At the top,
  remaining drag distance drives the reverse transition into Home. This replaces
  HOME-V2-05 pull-down reachability **on All apps**: one gesture cannot both
  lower its first row and leave the screen. Category-list reachability remains
  unchanged. Preserve the independent rail-jump positioning behavior.
- Treat the pair as vertically adjacent surfaces. During opening, Home moves
  upward as All apps rises from below; during closing, All apps moves downward
  as Home enters from above. Use a shared transition progress, stationary
  wallpaper and stable navigation chrome. No horizontal slide, abrupt screen
  swap, or crossfade as the normal-motion replacement for this relationship.
- Use a reversible distance/velocity threshold and settle to one screen on
  release. Cancel safely to the originating screen if the gesture is interrupted.
  Proposal: inertial scrolling alone does not cross a screen boundary; the
  pointer must still be dragging beyond it. This prevents a fast widget-stack
  fling from unexpectedly opening All apps. Test reverse direction mid-drag.
- Widgets retain their own taps, swipes, scrolling and long presses. Do not
  steal a widget's pointer stream after it has claimed the gesture. A separate
  edit mode exposes widget move/resize/remove handles. Cooperative nested-scroll
  widgets can pass unconsumed motion to the stack and then the screen transition.
  Widgets that consume their entire touch stream remain usable; provide exposed
  launcher gutters and the Apps action so navigation never depends on a provider
  supporting nested scroll. Do not overlay an invisible gesture catcher.
- The Apps action remains a direct alternative to scrolling to the bottom.
- Horizontal page gestures start in launcher-owned space, with directional
  locking and a deliberate threshold. System back edges and status-bar gestures
  retain Android behavior. Offer explicit page navigation in edit mode/settings.
- All apps moves through drawer category pages on horizontal row swipes, without
  changing the selected Home/widget page. Mega Sorter
  retains those gestures exclusively while active. Search, editors and folder
  popups similarly own their interactions before page navigation.
- Respect disabled/reduced animations with direct state changes or minimal
  feedback. Incoming headset events cannot move a row during an active touch.

## 5. Native Android widgets — WORKSPACE-04

Core hosts ordinary installed Android app widgets. Users do not need a Modular
add-on for each widget. Android widgets and Modular add-on pages are separate
extension mechanisms with separate lifecycle/security boundaries.

- Long press empty Home space or a widget to open the **Home screen** bottom
  sheet with Add widget, Edit and Advanced. Edit enables hold-and-drag movement,
  a bottom-left resize grip and a selected-widget removal icon; Done exits.
  Settings > Productivity > Home screens manages pages and widget configuration.
  The [grid v2 contract](home-widget-grid-v2.md) supersedes the original stack
  controls; Home has no permanent Add widget or Edit Home footer buttons.
- Show installed widget providers grouped by app, with label, available preview
  and sizing information. A missing preview never prevents choosing a provider.
- Use Android's widget binding/consent and provider configuration flows. Binding
  may require system consent; configuration may launch the provider's Activity.
  Do not describe either as Usage Access or ask users to allow restricted settings.
- Add the widget only after successful binding/configuration. Cancellation,
  denied binding, missing provider and interrupted configuration clean up staged
  widget IDs and preserve the existing layout.
- Stack layout defaults to full available content width and provider-compatible
  initial height. The [widget layout extension](widget-layout.md) adds half-width
  slots, configurable area width/margins/gaps, and per-widget height/padding.
  Resize within provider/platform bounds and communicate actual sizes to the
  host view. Free-positioned grid placement is outside this version.
- Edit mode supports reorder, supported resize, move to another widget page,
  provider reconfiguration when available, and Remove widget. Removing a widget
  releases its host binding; it never uninstalls its provider app.
- Persist instance identity, owning page, order and dimensions. Reconcile widget
  IDs with Android after process death; interrupted operations cannot orphan IDs
  indefinitely. Test cancellation/recreation at each external Activity step.
- Unavailable providers produce a bounded placeholder with Remove/recovery action.
  Do not silently bind another provider. Updates/reinstalls are reconciled against
  actual Android widget state; recreation may require user consent.
- Start/stop host listening with visible launcher lifecycle. Do not implement
  periodic Core refresh loops or keep every hidden page's view actively mounted.
  Provider-controlled widget work is outside Core's battery guarantees.
- Backups must not treat host-local widget IDs as portable. Cross-device restore
  requires explicit rebind handling; this draft does not promise widget backup.

## 6. Screen management and category migration — WORKSPACE-05

Settings > Productivity > Home screens shows small ordered page tiles, including
a distinct permanent Home tile. Add custom widget pages, rename them, reorder
left/right, disable without losing contents, and delete with confirmation of
widget removal. The last main Home cannot be deleted or disabled.

Reuse the existing category page organizer rather than creating two competing
orders. Category controls link into this unified organizer. A disabled category
retains its position and memberships, as today. Physical sides remain stable
under RTL; finger-left reveals the page physically to the right.

Existing category pages retain their relative sides/order around Home. The old
central alphabetical page becomes All apps below the new central Home. Category
"Show on Home" is relabeled/explained as "Show in main app list and Most Used"
without silently changing its stored filtering meaning. Global search and
explicit Hide behavior remain unchanged. Explicitly hidden apps cannot leak into
Listening, folders, widgets chosen by Core, or suggestions; third-party widgets
control their own contents and cannot inherit this guarantee automatically.

Custom pages initially host widgets and the same optional built-in sections;
Most Used has a single presentation at the top of All apps to avoid competing
pin orders.
Category pages remain app-list pages in v1; arbitrary widget/list mixing on them
is not part of this proposal. Deleting a page never deletes category memberships,
uninstalls apps, or deletes reusable folders.

## 7. Headset-connected apps — WORKSPACE-06

Settings > Productivity > Listening apps offers:

- Enable switch, off until chosen.
- Multi-select app picker with label/package search and manual ordering. Allow
  any launchable app, including audiobook/podcast players; unreliable manifest
  categorization must not restrict the choices to apps labeled Music.
- Trigger type: Any headset (default), Wired, USB, or Bluetooth headset output.
- Preview section and connection status. Saved choices remain editable while
  disconnected. No section appears until there is an eligible chosen app.

The section appears on main Home when a qualifying output is connected, and
hides when disconnected, without changing pins, folders, category membership,
page order or the app-list contents. The chosen apps retain their saved order.
They are explicit contextual shortcuts, like opened-folder contents: category
main-list suppression does not remove them, but explicit Hide/unavailable profile
and launch validation do. Duplicates within the section are removed by AppKey;
an app may also remain in Most Used or the alphabetical list.

Reuse and generalize the existing foreground `FolderConditionCoordinator` audio
signal. Detect output connection, not whether playback is active. Reconcile on
resume after a connection change while another app was open. Observe events
while needed and visible; no Bluetooth discovery, background service, periodic
polling, automatic app launch or playback. Built-in speakers do not count.
Unknown signal is not a claimed connection: keep configuration, hide the
contextual section and explain status in settings. Defer layout changes during
active gestures, preserving the scroll anchor afterward.

Any/Wired/USB/Bluetooth detection should use platform audio-device information
without Usage Access, notification access, location or microphone permission.
Do not promise identification of a specific named headset; that is a later,
separate option with platform-specific permission implications. Verify generic
connection reporting on the target Samsung device and different Android levels.

All shortcut launches use Core's existing exact AppKey validation and Most Used
learning path. Passive appearance of the section does not count as usage.
Third-party widget interactions are not automatically observable app launches;
Core must not infer usage from widget rendering or taps it does not own.

## 8. Future add-on pages — WORKSPACE-07

Reserve a typed page identity and capability boundary for future providers.
Do not expose an Add add-on page action until the capability actually exists.
The existing conceptual surface capability is not evidence of a working renderer.

Before implementing external pages, specify a versioned, bounded declarative
surface schema, authenticated actions, explicit capability grants, cancellation,
update limits, theme/accessibility semantics and failure behavior. Do not load
arbitrary third-party Compose code into Core. Android widget hosting follows the
platform host contract instead of pretending widgets are that future schema.

Core owns page selection, navigation, search/settings access and recovery. A
disabled, missing or failed page provider cannot replace the permanent Home or
trap navigation; return to Home with a recoverable status and retained settings.
No external page provider is required for this release.

## 9. Acceptance gate

| ID | Required evidence before completion |
| --- | --- |
| WS-A01 | Core-only/offline cold launch and HOME show Home; upward drag scrolls stack then opens list from below; reverse drag scrolls list to top then brings Home from above; short stacks, saved origin, Back and HOME reset work |
| WS-A02 | Always-visible sparse locale rail opens correct All apps section from Home with existing curve/bubble/settle behavior; continuous rail drag survives transition; category rail stays scoped; no chooser/accidental launch; package search works |
| WS-A03 | Widget/list/page/rail gestures have one owner; no boundary crossing before content end, no second swipe required, no fling-only crossing; test cancellation/reversal, nested and noncooperative widgets, category reachability and Mega Sorter |
| WS-A04 | Add/bind/configure/cancel/reconfigure/resize/move/remove real widgets; rotation and process death during configuration; no leaked host IDs or lost existing layout |
| WS-A05 | Provider missing/update/uninstall, unsupported sizes and loading errors retain usable Home/search/settings; placeholders recover safely |
| WS-A06 | Add/disable/delete/reorder pages on either side; migrate category order, filtering, pins, folders and themes without loss; idempotent migration and failed-save recovery |
| WS-A07 | Wired/USB/Bluetooth connect/disconnect and resume reconciliation; correct chosen apps/order; no Usage Access; explicit Hide and unavailable targets excluded |
| WS-A08 | No duplicate signal collectors, Core polling loops or hidden-page active views; measure idle/resource behavior and avoid claiming battery savings without evidence |
| WS-A09 | Native screenshots at 320/375/414/768dp, enlarged text, all themes, bright/busy wallpaper and RTL; real widget layouts, safe insets and reachable controls |
| WS-A10 | Existing package cache, launch, search, Most Used, categories, folders, add-on failure, wallpaper redraw and settings tests remain green |

Physical acceptance includes Samsung gesture navigation and wired/Bluetooth
devices. Generic emulator tests alone cannot establish those behaviors.
