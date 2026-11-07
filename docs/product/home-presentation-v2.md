<!-- Hallmark · reference study and specification · P4 H4 E4 S5 R4 V3 · native Android; rendering validation pending -->
# Home presentation v2

Status: **Source implemented; automated and physical acceptance tracked in PR #19 and the acceptance matrix.** Product request: 2026-11-07.

This target follows the Core baseline at
`3e5559f552d9218c23b67b3b45e73d1f3113839d`. It supersedes the earlier text-only,
add-on-only icons/alphabet-index requirements. Source implementation is present,
but the acceptance matrix below is not yet complete. See the [implementation plan](../implementation/home-presentation-v2-plan.md)
and [boundary decision](../decisions/ADR-0005-indexed-home-and-featured-addons.md).

## 1. Reference and design intent

Match the supplied Niagara screenshots (25438 and 25440) closely in the app list,
icon/text alignment, text size, letter dividers, right-side alphabet rail, and
the inward curve and selected-letter bubble during navigation. Retain Modular's
own settings, contracts, and reliability behavior.

Observed reference characteristics:

- One spacious, left-aligned column over a dimmed, full-screen wallpaper.
- App icons share a vertical gutter; single-line labels share a separate baseline.
- Section letters sit over the icon gutter, with extra space before each group.
- A compact right-side alphabet is straight at rest. Near the drag position it
  curves left, with a larger highlighted letter further left of the rail.
- White, regular sans-serif labels and heavier section letters. A blue accent
  is used sparingly for search and the active navigation bubble.

The screenshots establish two visual states, not exact animation timing, font
identity, screen density, or behavior on release. The values below are initial
calibration targets, to be checked on a device at matching density/font scale.
Use Android's native sans-serif family initially; do not claim it is Niagara's
exact font. The reference photograph is not an app asset and must not be bundled
or committed. Wallpaper comes from the user's device.

## 2. Ownership and defaults

| Feature | Owner | Target default |
| --- | --- | --- |
| Complete alphabetical app list, section letters, right-side navigation | Core | On |
| App icons | Core | On; a persisted Show app icons switch permits text-only rows |
| Background | Core | Existing theme's solid surface; system wallpaper is opt-in |
| Bottom search and settings gear | Core | Persistent, reachable without an add-on |
| Pull-down reachability | Core | On |
| Starred section contents, usage ranking, saved pins | Most Used add-on | Only after explicit Use on Home |
| Rendering and navigation of a valid contributed starred section | Core | Generic contract; no provider-ID special case |
| Profile subtitles, icon packs, widgets, replacement Home surfaces | Optional add-ons/future work | Not enabled by this change |

Core still launches immediately without accounts, network, Usage Access, extra
APKs, or manual instance/context configuration. App identity remains the exact
package, activity, and profile key; duplicate labels never collapse identities.
The new icon default applies on upgrade unless the user has explicitly stored
an icon preference. Existing theme choice, search state, and module configuration
are retained. Wallpaper stays off until chosen.

## 3. Core list and typography — HOME-V2-01

- The alphabetical catalog remains complete, with no fixed app-count limit.
- Locale-aware ordering and section bucketing use the same locale rules. Numbers,
  punctuation, and labels without a usable alphabetic bucket appear under `#`.
  Locale-specific letters, including Norwegian Æ, Ø, Å, are handled explicitly;
  accented names must not become unreachable through an ASCII-only index.
- Insert one visible letter divider before each populated alphabetical section.
  Headers scroll with their sections; do not introduce a sticky top toolbar.
- Use platform app/adaptive icons with intrinsic appearance preserved. Do not
  force every app into a new circular background or add profile subtitles.
  Missing/loading icons have a stable-size neutral placeholder; they never block
  the label or launch action. Icon loading/caching must not run on the UI thread.
- Initial reference calibration at a 411 dp wide phone: icon 40 dp, row pitch
  60 dp, regular app label 18 sp, section letter 22 sp medium, icon start about
  52 dp, label start about 116 dp. These correspond approximately to icon/text
  starts at 13%/29% of the supplied screenshot width. Header aligns to icon start.
- Adapt the leading gutter and text width on compact screens; reserve a separate
  48 dp right-side gesture lane. Long labels ellipsize visually with the full
  accessible label retained. Font scaling may increase row height. Hiding icons
  retains the label alignment and section structure instead of shifting on scroll.
- Wallpaper mode uses light text, a readable dimming layer, and subtle text shadow
  only if needed. Solid mode uses the selected Light/Dark/AMOLED theme tokens.

## 4. Optional background — HOME-V2-02

- Settings > Appearance offers **Home background: Theme / System wallpaper** and
  **Wallpaper dimming** (0–80%, initial 35%). Settings and dialogs stay on opaque
  themed surfaces; wallpaper is confined to Home.
- Prefer showing Android's wallpaper behind a transparent Home window, including
  live wallpaper when supported. Do not require broad storage/photo access or
  copy Android's wallpaper bitmap. Offer Android's wallpaper chooser if the user
  wants to change the image; app-owned image import is outside this slice.
- Wallpaper remains stationary when the app list scrolls, the rail curves, or
  the list is pulled down. Content/background extend behind system bars while
  controls and gestures respect their safe insets.
- Use a local scrim under bottom controls and adequate label treatment so bright
  wallpapers remain readable even at low dimming. Target 4.5:1 for body labels
  and 3:1 for controls. Verify bright, dark, busy, and live wallpaper cases.
- Switching back to Theme restores the persisted built-in theme immediately.
  Unsupported/failed wallpaper display falls back to that theme without losing
  Home, search, or settings. AMOLED's true-black background applies in Theme mode.

## 5. Alphabet navigation and animation — HOME-V2-03

- Display `#` and the locale's alphabet in a right-side rail. Keep empty letters
  visible but subdued; tapping one goes to the next populated bucket, or the
  previous populated bucket if none follows. The feedback shows the resolved
  destination. Numeric/symbol apps remain directly reachable through `#`.
- Tap a letter to bring its header into view. During press-and-slide navigation,
  target the selected header near the upper third of the usable list viewport,
  matching the selected-K reference state; allow leading/trailing navigation
  space at list boundaries so edge sections remain reachable. Retain that section
  anchor on release. Press-and-slide continuously to navigate. Use absolute finger position in the fixed rail lane for hit mapping;
  visual displacement must not cause feedback loops or change the touched bucket.
- During drag, letters nearest the finger bow inward to the left; displacement
  decays smoothly with distance. Show a larger selected-letter bubble left of
  the curve, clear of the finger. Initial targets: rail text 18 sp, selected text
  30 sp, bubble 48 dp, maximum inward displacement 120 dp, settle duration 180 ms.
  At the 411 dp reference width, the resting letters center about 32 dp from the
  right edge. The rail occupies the lower ~72% of the list viewport; its touch
  lane stays fixed while the visual curve and bubble move inward.
  Bound all displacement within the viewport. Tune against the two supplied states.
- Keep rail motion responsive to the current finger position. Cancel obsolete
  scroll targets; do not queue a long scroll animation for every letter crossed.
  A tap may use a short scroll transition; dragging should track directly.
- On release/cancel, settle the rail to its straight rest state and remove the
  bubble. The selected section stays in view. No app may launch from the rail
  pointer sequence. Optional light haptics follow the platform preference.
- Anchor the rail above the bottom controls and below status/cutout areas. Keep
  every locale label visible, including `★`, `#`, and locale-specific letters.
  Size each glyph and line height to its actual slot; use more of the list height
  on short viewports. Preserve each letter's position in the full alphabet hit
  map. A tap jumps directly to that letter (or its next populated bucket); it
  must never open the section chooser. The chooser remains an explicit
  accessibility action. Rail labels are one composite
  drag control, not a claim that every tiny glyph has a separate 48 dp target.
- Provide discrete next/previous section actions and a section chooser for users
  who cannot drag. Respect disabled/reduced system animations with direct jumps
  and minimal feedback. Do not add ornamental motion to app rows.

## 6. Bottom controls and search — HOME-V2-04

- Move the existing persistent search bar and settings gear to the bottom of Home,
  above the navigation/gesture inset. Keep their existing 48 dp baseline height;
  search fills the remaining width and neither control expands on focus/typing.
  No Apps title or top search toolbar returns.
- The bottom row remains fixed when the list scrolls or is pulled down. Reserve
  content padding so the last app is fully visible and tappable above it.
- When the keyboard opens, move controls above the IME and resize the app viewport.
  Search all launchable apps, including pinned/featured ones, returning one result
  per app key in alphabetical order. Hide starred duplication and the alphabet rail
  for nonempty search results. Clearing restores the normal sections and prior
  section/scroll anchor where available.
- Back first dismisses editing/IME; Settings remains reachable. Preserve the
  existing HOME reset, settings return, and launch-return semantics. A new HOME
  intent clears search and transient reachability, not saved pins or preferences.

## 7. Pull-down reachability — HOME-V2-05

- Starting on list content at its top boundary, drag downward to insert a
  controlled leading space. The first app row must be movable to approximately
  the middle of the usable screen. This is a reachability gesture, not refresh.
- Apply resistance and cap the offset at the distance needed to reach that
  midpoint. On release retain the lowered position, so the user can tap the app.
  A normal edge stretch that immediately snaps back does not meet this requirement.
- Dragging upward consumes the reachability offset first, then scrolls normally.
  An explicit HOME reset, search start, or explicit alphabet jump clears it.
  Ordinary return from a launched app/settings and rotation preserve the visible
  app anchor and clamped offset; cold process start resets this transient offset.
- Move the list only: wallpaper, bottom controls, and rail remain fixed. Use one
  coordinated scroll/gesture state so rail dragging, list scrolling, taps, and
  reachability cannot fight. A recognized drag suppresses the row's launch click.
- Do not intercept the Android status-bar notification gesture or system back
  edges. Cancel safely on interruption, pointer cancellation, and catalog changes.

## 8. Most Used starred section and pins — HOME-V2-06

- When the upgraded Most Used module is selected with **Use on Home**, it
  contributes a `★` section before `#`/letters and a star at the top of the rail.
  Selecting the star navigates to that section with the same gesture feedback.
- Section order: saved pins in user-defined order, then unpinned suggested apps
  by recorded foreground time descending, with deterministic alphabetical ties.
  Default suggested count is 8, configurable from 0–20; pins are additional,
  bounded to 50. The existing 1–30 day window remains configurable.
- The complete alphabetical catalog follows. A featured app also appears once
  in its alphabetical section so letter navigation always finds it. Deduplicate
  within the starred section, across pins/suggestions, and in search results.
- Long-press an app offers **Pin to starred apps** or **Unpin from starred apps** when
  the compatible provider is selected. Its module Settings also exposes an app
  picker and a **Pinned apps** list with reorder/remove controls. Provide button
  alternatives to drag reordering. No JSON or manual instances/contexts are needed.
- Most Used owns persistent pins per configured instance, keyed by exact activity
  and profile, and preserves manual order across Core/add-on restarts and upgrades.
  Core renders generic validated pin controls and sends authenticated actions.
  Do not store pins by app label or silently transfer them to another profile.
- Pinning works without Usage Access. With access denied/revoked, show pins only
  and a permission action in the starred section; never invent usage rankings.
  With permission but no history, show pins and a clear no-history state.
- Usage Access is a special Android setting, not an installation-time runtime
  permission. On the first opening without access, Most Used offers an explicit
  **Open Usage Access** / **Not now** choice and remembers the user's choice.
  Its settings retain a manual access button and package-targeted App info shortcut,
  with instructions for **Allow restricted settings** when Android blocks the
  toggle. The add-on must not redirect automatically or bypass platform restrictions.
  An enabled, connected provider with no entries still has a star destination
  containing a useful Pin apps / permission action, not an empty dead-end screen.
- Uninstalled/unavailable pins are excluded from actionable Home rows and shown
  as unavailable in module Settings so they can be removed. Preserve a missing
  pin for reinstall of the same exact key. Paused profiles remain non-actionable.
- Core does not calculate usage ranks. Disabling/uninstalling/losing the selected
  add-on removes its star contribution and actions, retaining the usable complete
  alphabetical catalog. Retained pins reappear after the same provider reconnects.
  Add-on uninstall clears its private data according to Android's normal behavior.

## 9. Framework and compatibility — HOME-V2-07

Introduce a small versioned **featured-apps capability**, separate from the
existing permutation-only `apps.sort/v1`. It returns bounded app keys and feature
state, not executable UI, icons, labels, arbitrary intents, or usage histories.
Core owns row data, icons, sections, animation, and navigation.

Core must validate actionable membership in the current catalog, duplicate keys, limits,
instance/session identity, catalog/configuration generations, and late results.
Saved pins absent from the catalog may appear only as unavailable management
entries; unknown suggested keys are invalid. Adding a pin requires a current
catalog key; removing/reordering saved pins must also work for unavailable apps.
Pin/unpin/reorder requests must be authenticated, bounded, idempotent where
appropriate, and acknowledged only after persistence. Failed saves retain the
previous state and show an actionable error. Do not log pin lists or usage data.

The public SDK supplies the contract so another compatible add-on can contribute
without hard-coded Most Used package checks. One featured provider is active at a
time in this slice. Core's ordinary module Settings handles selection automatically.

Do not alter the wire layout of existing sort parcels. Old Core/add-on pairings
must negotiate supported capabilities safely. Upgraded Most Used may continue
to offer v1 sorting for old Core. New Core should atomically migrate an active,
compatible Most Used selection to its featured capability, retaining day settings
and leaving the original advanced context intact. Other legacy sort providers
remain selectable; non-alphabetical legacy ordering uses a flat list with the
alphabet rail/letter dividers suppressed, rather than misleading letter jumps.
Return to built-in alphabetical mode restores the rail and dividers.

## 10. Acceptance and completion gate

| ID | Required evidence |
| --- | --- |
| V2-A1 | Core-only, offline install: icons, all apps, locale section letters, side navigation, bottom search/gear; no star or module dependency. |
| V2-A2 | Match reference alignment/density at equivalent dp/font scale; capture rest and active rail states at 320/375/414/768 dp and large text. Exact font/timing claims require measurement. |
| V2-A3 | Wallpaper off/on, bright/busy/dark/live backgrounds, dimming persistence, fallback, and solid Light/Dark/AMOLED remain readable and usable. |
| V2-A4 | Tap/slide rail through #, A, K, Z and locale-specific buckets; handle missing buckets, duplicates, rapid changes, interruptions, rotation, RTL labels, reduced motion, and short viewports. |
| V2-A5 | Pull first row to mid-screen, release, launch it; test upward restoration, HOME/search reset, app/settings return, no accidental launches, and notification/back gesture coexistence. |
| V2-A6 | Bottom controls stay usable with IME/navigation modes; search yields unique complete matches, clearing restores sections, last row is not obscured. |
| V2-A7 | On a real cross-APK path, Use on Home creates star; a foreground-used app ranks above an unused one; pins outrank usage and retain custom order after restarts. |
| V2-A8 | Pin/unpin/reorder through ordinary UI; duplicate labels/profiles, uninstall/reinstall, denied/revoked Usage Access, zero history, failed writes, and empty star all behave as specified. |
| V2-A9 | Provider death/removal, timeout, malformed/stale keys, configuration changes, untrusted callers and old/new versions preserve Core and its complete catalog. |
| V2-A10 | No main-thread icon decode/IPC, no unbounded caches, no accumulating animation jobs; exercise 1,000 apps and rapid rail scrubbing on emulator and a representative physical phone. |

Implementation is complete only after the acceptance evidence is recorded for its
specific revision. Existing baseline tests and earlier user acceptance do not
automatically cover these new requirements. Samsung/system gesture and wallpaper
behavior need physical-device checks; automated coverage should precede that pass.

Current source paths and pending evidence for V2-A1 through V2-A10 are tracked in
the [v2 implementation matrix](../implementation/core-completion.md#home-presentation-v2-implementation-and-acceptance).
