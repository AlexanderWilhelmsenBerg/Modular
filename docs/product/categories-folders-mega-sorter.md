<!-- Hallmark · reference study and specification · P4 H4 E4 S5 R5 V4 · native Android; implementation and rendered validation pending -->
# Categories, popup folders, and Mega Sorter

Status: **Implementation in progress; release acceptance pending.** Request and clarification: 2026-11-08.
Baseline: `b1b133b3723dd092e4ab083f22ade975098facc8` (Core local Most Used).

This is the next Core feature specification after Most Used. It does not claim
that category pages, folders, or swipe sorting are in an available APK. See the
[implementation plan](../implementation/categories-folders-mega-sorter-plan.md)
and [ADR-0008](../decisions/ADR-0008-core-app-organization.md).

Implementation was authorized on 2026-11-08. The branch now contains category
pages/settings, popup folders, smart visibility and Mega Sorter. Proposed defaults
below were used as the implementation baseline; recommendations in section 6.3
remain future work. Validation and physical-device limits are tracked in
[the acceptance record](../implementation/category-validation.md).

## 1. Product contract and decisions

The user requested three connected Core functions:

- **Categories:** optional pages of apps, configured in Settings. Default named
  categories are Games, Media, Social, Productivity, Work, and Finance. Each has
  an enable switch and a switch controlling whether its apps also appear on Home.
  Users can create custom categories and order pages using small settings tiles.
- **Folders:** popup app collections, placed in the Most Used section or the main
  app list. Putting an app in a folder does not remove its standalone app entry.
  Each folder has its own settings and optional smart visibility: headset
  connection, time schedules, selected Wi-Fi networks, or areas defined on a map.
  Hidden folders remain listed and editable in Settings.
- **Mega Sorter:** choose one or two category destinations, then swipe app rows
  toward them. Blue identifies the right destination and green the left. A
  top-center X exits. The assigned row swipes away and returns from a small
  center dot into a colored row, without changing row height or blocking other
  rows or vertical scrolling.

Confirmed clarifications:

- An app may belong to several categories.
- Home search finds all non-hidden apps, including apps omitted from the main
  list because they belong to category pages.
- Any category may occupy either side of Home. Games was an example, not a fixed
  left-side destination.
- Folder conditions support **Match any** and **Match all**, defaulting to
  **Match any**. Location/Wi-Fi behavior must use few resources, not continuous
  power-hungry tracking. The user requested map-defined areas as well as Wi-Fi.

The following defaults resolve details not explicitly selected by the user.
They are **proposals in this draft**, not additional confirmed requests:

| Detail | Proposed behavior |
| --- | --- |
| Initial state | Categories and all six category pages off; Show on Home on |
| Screen direction | Tiles represent physical page positions; normal finger-left swipe reveals the page on the right |
| Multiple-category visibility | Any enabled member category with Show on Home off suppresses direct Home entries |
| Pinned apps and visibility | The same Home suppression applies to pins and suggestions; saved pins are retained |
| Folder placement | One shortcut per folder, in either Most Used or the app-list Folders section |
| Folder independence | Folders work when category pages are disabled |
| Folder contents | Manual ordered list, new members appended; no nested folders in this version |
| Smart-folder evaluation | Refresh while the launcher is visible; no background tracking by default |
| Map areas | Named circles with adjustable center/radius initially; arbitrary polygons remain a possible extension |
| Unknown condition | Show shortcut with an explanation in folder settings; user may choose Hide instead |
| Sorter assignment | Adds membership; never silently removes other category memberships |
| Sorter colors | Visible in Mega Sorter only; normal app rows retain the existing appearance |

## 2. Category model and settings — ORG-01

Settings exposes **Categories and folders** with a Categories master switch,
the six built-in categories, Create category, Manage folders, page ordering, and
Start Mega Sorter. These are ordinary Core settings, not module instances or
launcher contexts. Basic organization requires no extra APK, account, network,
or Usage Access.
Optional map tiles need network access; location/Wi-Fi conditions require the
relevant Android permissions. Ordinary organization and other folder rules keep
working when those permissions are denied.

Each category has a stable ID, editable display name, enabled flag, Show on Home
flag, automatic-matching policy, and manual membership overrides. Names never
serve as identity. Built-in IDs remain stable through rename, language changes,
and upgrades. Custom names are user text and are not translated automatically.

- **Enabled** adds/removes that category's page. Disabling preserves its name,
  order, memberships, and other preferences.
- **Show on Home** controls direct app rows on the Home page, not membership,
  installation, global search, or visibility inside an explicitly opened folder.
  Turning it off shows the affected count before confirmation, including any
  affected pins. Existing pins are retained for later restoration.
- The Categories master switch disables all category pages and all
  category-driven Home suppression, without deleting organization data.
- Per-category **Automatic matching** can be enabled for supported built-in
  types. It defaults on for Games, Media, Social, and Productivity, but does not
  enable their pages. Work, Finance, and custom categories start manual-only.
- Settings supports rename, member management, resetting manual overrides to
  automatic behavior, and deleting custom categories with confirmation.
  Built-in categories can be disabled/reset rather than deleted.
- Deleting a category removes only that category and its overrides/order entry.
  It does not uninstall apps, delete folders, or remove other memberships.
- Blank/whitespace names and duplicate category names after locale-aware
  comparison are rejected with inline errors. Folder names have a separate
  namespace, so a Games folder and Games category can coexist and are labeled
  by type in pickers. Implementation must document and validate storage/name
  limits, with errors instead of silently dropping entries.

Creating a category from Settings offers its name, page enabled state, Show on
Home state, and position. Default page placement is the next slot to the right
of Home; users can move it to either side. Long-press creation uses this same
editor and returns to the original app's destination picker.

## 3. Automatic categorization — ORG-02

Use the local Android `ApplicationInfo.category` metadata exposed for launchable
apps. Android has a small taxonomy; it does not provide every app's Google Play
store category. Automatic placement is therefore partial, explainable, and
correctable, not a guarantee that every app is classified.

| Android metadata | Initial Modular match |
| --- | --- |
| `CATEGORY_GAME` | Games |
| `CATEGORY_AUDIO`, `CATEGORY_VIDEO`, `CATEGORY_IMAGE` | Media |
| `CATEGORY_SOCIAL` | Social |
| `CATEGORY_PRODUCTIVITY` | Productivity |
| `CATEGORY_UNDEFINED` | No automatic assignment |
| `CATEGORY_NEWS`, `CATEGORY_MAPS`, `CATEGORY_ACCESSIBILITY`, unknown future values | No automatic assignment in v1 |
| Work or Finance | No corresponding Android category; manual/Mega Sorter assignment |

Only when category metadata is undefined may the deprecated `FLAG_IS_GAME` be
used as a compatibility hint for Games. An explicit non-game category wins over
that flag. Do not guess Finance from a banking-looking name, or Work from a
package prefix. A managed work profile is not equivalent to the user's Work
category; this version neither creates profiles nor automatically puts personal
or managed-profile apps into Work.

Metadata comes from app manifests or installer hints. Missing or inaccurate
metadata is expected. Show assignment provenance in member management, such as
**Android category** or **Added by you**. Let users correct each category
independently with these three states:

| Manual override for app/category | Effective membership |
| --- | --- |
| Include | Included regardless of automatic metadata |
| Exclude | Excluded even if an automatic rule matches |
| Auto / no override | Included only if automatic matching is enabled and matches |

Adding manually or with Mega Sorter sets Include. Removing a member sets Exclude
so refresh cannot immediately add it back. Reset to automatic clears that
category's override. Adding to one category never clears another category's
override. Disabling automatic matching leaves manual inclusions intact.

Reuse the existing app repository and cache. Cache the category signal alongside
app metadata, version the cache format, and refresh affected packages when
installed/updated/changed. New matches appear without rescanning every package
on each draw. A stale/missing cache field is unknown until reconciled. Metadata
read failures preserve the last usable view and never discard manual choices.

Do not request `QUERY_ALL_PACKAGES` merely to implement this feature, call
`setApplicationCategoryHint` for third-party apps, scrape app stores, upload
installed package lists, or infer categories from Usage Access. A future
reviewable metadata provider can extend classification under the constraints
below; none is an assumed complete classifier or enabled in this release.

### 3.1 Online metadata options and recommendation

Research checked 2026-11-08. These are candidates, not contracted services or
implemented integrations. No account has been created and no package list sent.

| Source | Useful metadata and access | Fit and limitation |
| --- | --- | --- |
| [F-Droid indexes](https://f-droid.org/en/docs/All_our_APIs/) and [build metadata](https://f-droid.org/docs/Build_Metadata_Reference/) | Public repository indexes and package metadata, including categories | Useful open catalog supplement without API credentials; covers apps in the selected repositories, not the full Play catalog; map repository-specific categories explicitly |
| [42matters Lookup API](https://42matters.com/docs/app-market-data/android/apps/lookup) | Google Play app metadata by package identifier, including category; authenticated API, Tiny plan or above | Direct package lookup; commercial quota/plan and caching/redistribution terms need agreement before a launcher ships it |
| [AppBrain API](https://www.appbrain.com/docs/api/specification) and [pricing](https://www.appbrain.com/docs/api/pricing) | Google Play app information/categories by package, API key and credit quotas | Published free allowance is 500 credits/month, then paid plans; suitable for a small evaluation, not a free unlimited service for every user |
| [Appfigures Public Data API](https://help.appfigures.com/en/article/appfigures-api-cli-and-mcp-access-limits-and-public-data-1seiibo/) | App metadata across stores; public-data add-on and purchased credits | Access to apps beyond the developer's own catalog; their terms require contacting them for commercial uses beyond internal analysis, so ordinary API access is not a redistribution license |
| [Google Play Catalog](https://developers.google.com/android-publisher/play-catalog) | Eligible catalog exports/changes for registered third-party app stores | Not a general public package-to-category API for a launcher; do not base this feature on obtaining that access |

Recommend Android metadata first, then evaluate an optional F-Droid catalog
supplement. Evaluate commercial lookup only if measured coverage justifies its
recurring cost and permitted use. Finance may be enriched from store categories;
Work remains a personal choice rather than a universally correct classification.
No source guarantees coverage of system apps, private builds, or every region.

Before enabling any provider, specify its category mapping, provenance, conflict
policy, cache expiry, quota/failure behavior, and permitted storage/redistribution.
Manual Include/Exclude always wins. An offline cache must keep Home usable when
the provider fails; avoid lookups per launch or frame. Downloading a permitted
catalog for local matching can avoid sending installed-app identities, but still
requires source/license validation and bounded updates. A per-package online
lookup exposes that package to the service: make it opt-in and minimize requests.
Do not put a shared paid-service secret in the APK. A backend or user-owned key
requires a separately specified operating model; neither is implicit in this draft.
An open client library does not itself grant rights to redistribute provider data.

Evidence checked on 2026-11-08:

- [ApplicationInfo categories and game flag](https://developer.android.com/reference/android/content/pm/ApplicationInfo): category metadata is API 26+, can be undefined, and the game flag is deprecated.
- [LauncherActivityInfo](https://developer.android.com/reference/android/content/pm/LauncherActivityInfo) and [LauncherApps](https://developer.android.com/reference/android/content/pm/LauncherApps): launchable activities and their application metadata for accessible profiles.
- [PackageManager](https://developer.android.com/reference/android/content/pm/PackageManager): only an installer can set category hints for its installed packages; launcher-local organization does not require writing those hints.
- [Google Play Catalog API](https://developers.google.com/android-publisher/play-catalog): intended for registered third-party app stores, not a general unauthenticated launcher category lookup.
- [Android package visibility](https://developer.android.com/training/package-visibility) and [Play visibility policy](https://support.google.com/googleplay/android-developer/answer/10158779): broad package visibility is a separate, restricted decision.

## 4. Membership, visibility, search, and Most Used — ORG-03

Membership refers to the existing exact `AppKey` (package, activity, profile
serial). Android's package-level category hint can match each of that package's
launchable activities; manual choices apply to the selected exact entry.

Let `V` be the catalog after existing explicit Hide app rules. Let `M(c)` be
effective membership in category `c`, intersected with `V`. If categories are
enabled, direct Home app rows are:

`V minus union(M(c) for enabled c where Show on Home is off)`.

When categories are disabled, direct Home app rows are `V`. This rule filters
both the alphabetical list and direct Most Used app rows, including pins and
suggestions. It does not mutate the catalog, pin storage, or learning history.
Apply the same projection to direct starred app rows from external featured
providers without modifying their returned data or IPC contract. Built-in
suggestion eligibility is filtered before applying its count limit; filtering a
bounded external result may leave fewer entries and must not invent replacements.
Turning off a category page removes that category's suppression contribution;
an app still suppressed by another enabled category remains off Home.
If organization state cannot be loaded, keep the last validated projection. If
none exists, show normal Home with category filtering disabled and a recoverable
settings error; never leave the launcher empty or discard the stored document.
Existing explicit Hide rules still apply to that fallback.

- Category pages show their members regardless of Show on Home.
- Home search uses all of `V`, matching display name and package identifier as
  today. Every app occurs once in search, even if in several categories/folders.
- A small result hint may identify its categories; tapping still launches the
  exact app directly. Search does not require visiting its category first.
- Folder membership alone never suppresses any standalone entry. An app can
  still be omitted from direct Home rows by a category switch or explicit Hide
  app; these are separate controls.
- Explicit Hide app continues to remove an app from all launcher browsing,
  search, folders, and suggestions. Keep its memberships for restoration through
  the existing hidden-app settings.
- Uninstalled apps do not remain launchable. Preserve manual references as
  unavailable entries in management; a returning exact identity can restore
  them. Hidden, paused, and unavailable states never launch a different profile
  or similarly named app as fallback.
- Successful launches from category pages, folder contents, and search must use
  the same Core launch/learning path as Home. Opening a folder or changing pages
  does not count as an app launch. Existing Most Used reset/source behavior stays
  intact.

This refines earlier requirements that every non-hidden app must always appear
in the main alphabetical list. The complete underlying catalog and global app
search remain available; an explicit category switch can filter Home.

## 5. Category pages and page-order tiles — ORG-04

Use one horizontal page sequence with exactly one permanent Home page and one
page per enabled category. Any category can appear before or after Home.
Disabled pages retain their saved relative order but are omitted from navigation.

Settings shows compact named tiles, including a distinct Home tile. Drag a
category tile across Home to change its side, or within a side to change its
distance/order. Home cannot be removed or disabled. Provide Move left/right
actions alongside dragging. Show a preview with physical left/right labels and
swipe arrows; names alone must not imply finger direction.

Example physical page order: `Work | Home | Games | Media`. From Home, dragging
a finger right reveals Work; dragging left reveals Games, then Media. This is
the proposed standard pager convention, not a fixed Games destination. Preserve
configured physical sides in RTL layouts; translate labels and render names
correctly without silently swapping saved sides. Pages do not wrap at the ends.

Each category page:

- Has a compact category-name heading and its locale-alphabetical app list.
- Reuses Home icons, row sizing, wallpaper/theme, populated letter dividers and
  alphabet navigation, vertical scrolling, and pull-down reachability.
- Shows only populated letters for its own visible members, including locale
  letters. It has no Most Used star or folder rail destination of its own in v1.
- Shares bottom search/settings controls. Search is global: entering a query
  shows the global app results while retaining the origin page and its scroll
  anchor; clearing/back from search restores that page. Disable page swipes
  while editing/search results are active, rather than making a row drag change
  both search scope and page.
- Has an empty state with Add apps and Start Mega Sorter actions. Empty enabled
  categories remain selectable and configurable.

Preserve scroll/reachability by stable page ID through page switching, Settings
round trips, and ordinary recreation. Removing/disabling the current category
returns to Home safely. Android HOME always returns to Home, dismisses transient
UI, and ends Mega Sorter while retaining accepted assignments. Back dismisses
the current popup/editor/search before returning from a category to Home.

## 6. Popup folders — ORG-05

A folder has a stable ID, name, ordered exact-app memberships, and shortcut
placement. Users can create, rename, move, reorder members, remove members, and
delete folders in Manage folders and through folder actions. Apps may be in
multiple folders. Deleting a folder only removes that collection/shortcut.

Placement choices:

- **App list:** a Folders section follows alphabetical apps, as in the supplied
  reference. Shortcuts sort by folder display name with stable-ID tie-breaking.
  A labeled folder rail destination appears only when this section is nonempty.
- **Most Used:** a manually placed folder shortcut participates in the ordered
  pinned section before app suggestions. Folders are not usage-ranked. Existing
  app pins retain their order on migration; the existing 50-pin limit counts app
  and folder shortcuts together. This placement requires Most Used to be on;
  explain that requirement instead of silently enabling it. Turning Most Used
  off retains these folders, editable in Manage folders.

A folder has one shortcut placement in v1. Moving it changes the placement, not
its identity or membership. Its contents remain directly available elsewhere
according to the app visibility rules. No nested folders, filesystem directories,
or category-to-folder live mirroring in this version. Smart rules below control
shortcut visibility; they do not automatically select or change folder members.

Tap the shortcut to open an anchored rounded popup above the launcher surface.
It contains a folder title and a scrollable vertical icon/name list. Use the
same app row vocabulary as Home, adapted to the popup's width. If space around
the shortcut is insufficient, position the popup within safe screen bounds;
it must not cover system navigation or depend on a full-screen replacement page.
Keep the folder title and close action reachable while content scrolls.

Outside tap, Back, or Close dismisses the popup and restores focus/scroll to the
folder shortcut. While open, its scrim prevents launching an underlying app or
swiping the underlying page. App tap launches directly; app long press opens
the existing action sheet with organization actions and Remove from this folder.
Only one popup/sheet is interactive at a time. Empty folders show Add apps.
Hidden/unavailable members are managed through explicit status rather than
rendered as deceptively launchable entries.

### 6.1 Per-folder settings and smart visibility — ORG-09

**Manage folders always lists every saved folder**, even if its shortcut is
manually hidden, its rules do not match, it has no available members, or its
Most Used placement is disabled. Each row shows placement, current visibility,
and the reason; it opens that folder's settings and a preview of its contents.
The same settings are reachable from folder shortcut actions and the popup.
Never require the condition to match before allowing a folder to be edited.

Each folder editor contains name, placement, members/order, visibility mode,
condition list, Match any/all, missing-signal behavior, current condition status,
and Delete. Modes are **Always show** (default), **Smart visibility**, and
**Manually hide**. Switching modes preserves saved rules and membership. A manual
Show/Hide selection persists until the user chooses Smart visibility again;
make the override explicit rather than silently forgetting it on restart.

Smart visibility supports these requested conditions in Core:

| Condition | Controls and meaning |
| --- | --- |
| Headset connected | Any supported wired, USB, or Bluetooth headphone/headset output is connected; optionally choose one of these types. Connection is independent of playback. Do not treat all speakers/HDMI outputs as headphones |
| Time schedule | Selected weekdays and local start/end times; support overnight ranges. A Monday 22:00–06:00 interval ends Tuesday morning. Several intervals can be configured; any interval matches this condition |
| Connected Wi-Fi | One or more explicitly saved network names; matches the current Wi-Fi connection, not a nearby scan result. Internet access is not required. Explain that an SSID is not a secure or unique place identity |
| Inside a saved area | Create/name an area on a map, then match a usable location fix against it. Proposed initial editor: move center and resize a circular boundary, showing radius and current accuracy; multiple saved areas can be referenced |

Map editing must allow pan/zoom and placing a boundary without granting current
location access. **Use my location** requests permission when used; evaluation
needs foreground location access. Start with a GMS-independent renderer and
location abstraction suitable for both distribution targets. The initial
implementation uses native map drawing and on-demand OpenStreetMap public tiles;
it is not a promise of free unlimited map hosting. Include attribution and comply with tile caching
and request limits. [OSM's public tile policy](https://operations.osmfoundation.org/policies/tiles/)
does not permit bulk/offline prefetch. Map-load failure must not erase saved
areas or stop their local evaluation; offer numeric center/radius editing as a
fallback. Arbitrary polygons and address-search/geocoding need separate scope.

A folder can combine different condition types. **Match any** is the confirmed
default; offer **Match all**. Example: Work can appear when connected to office
Wi-Fi OR inside the Work area. Choose All for inside Work AND weekday hours.
There is one flat rule group per folder initially; no nested expression editor.
Within a condition, any selected SSID/area/interval matches that condition.
Folder-level Any/All combines condition rows. Requiring both area A and area B
therefore uses two area rows with All, not two selections inside one row.
Reject enabling Smart visibility with no enabled conditions, without hiding the
folder or discarding its draft. Several matching folders may show together.

Conditions evaluate to Match / No match / Unknown, with a readable reason and
last-evaluated time. Any returns Match if any rule matches, No match if all do
not match, otherwise Unknown. All returns No match if any rule does not match,
Match if all match, otherwise Unknown. Proposed Unknown fallback is **Show**,
with an optional per-folder **Hide until known**. Permission denial, disabled
Location, unreadable Wi-Fi identity and stale/inaccurate fixes are Unknown, not
proof that the user left an area. Show recovery actions only in settings; do not
open Android permission screens automatically on Home or when a rule changes.
Explain the chosen fallback when configuring rules: Show may display the folder
even when its location/network cannot be checked; Hide keeps its shortcut absent
until known, while Settings still provides access. Do not present this as secure
app concealment or force warning badges into the ordinary app list.

Rules affect only the shortcut's eligibility, never saved contents, app hiding,
pin order, category membership, or local usage history. Normal placement gates
still apply: Always show does not enable Most Used, and a hidden folder keeps its
saved pin slot and counts toward the existing pin limit. When every app-list
folder is ineligible, omit the empty Folders section/rail target. Global search
continues to search apps, not conditionally hidden folder shortcuts.

Do not remove a shortcut under an active press/drag or close an open popup when
a signal changes. Hold the affected presentation until the interaction/popup
ends, then apply the newest projection and preserve the nearest scroll anchor.
Rules do not launch apps, navigate pages, send notifications, or reorder apps.
They are convenience rules, not a privacy/security boundary.

### 6.2 Resource, permission, and lifecycle contract — ORG-10

The user wants place awareness without significant battery use. The proposed
baseline refreshes on launcher visibility/resume and listens to relevant
callbacks while visible; it does not track travel while another app is open.
A folder can therefore update shortly after returning Home, not necessarily at
the moment of arrival in the background. Background geofencing is deferred,
not silently enabled as part of a map-area rule.

- Share one condition coordinator across folders; no polling loop or location
  listener per folder. Subscribe only to signals needed by active smart rules.
- Headset detection uses current audio output devices plus audio-device callbacks.
  Refresh after process recreation; do not mistake paired devices for connected
  outputs or audio activity for connection.
- Wi-Fi uses current connectivity changes and the connected network identity;
  do not initiate Wi-Fi scans. Unreadable identity is Unknown. Android treats
  SSID as location-sensitive: Nearby Wi-Fi permission alone is not a guarantee
  of access; account for fine-location permission and Location service state.
- Location first considers a recent cached fix with known age/accuracy. Proposed
  bounds: at most two minutes old; one shared foreground refresh request with
  a ten-second timeout, at most once per minute, using a low-power/balanced
  provider where available. Cancel on leaving the launcher; no continuous GPS,
  foreground tracking service, wake lock, or background-location request in this
  baseline. Where no suitable provider/fix exists, report Unknown and allow a
  user-initiated retry; never silently escalate to high-accuracy tracking.
- Boundary decisions account for accuracy: classify inside only when the
  uncertainty circle is wholly inside, outside when wholly outside. Boundary
  overlap is indeterminate; retain the last conclusive state for at most two
  minutes, then use Unknown fallback. Permission revocation, Location-off or
  provider-disabled invalidates retained area state immediately; an unreadable
  Wi-Fi identity likewise invalidates the corresponding Wi-Fi result. The short
  retention applies only to uncertain boundary fixes while location remains
  available. This proposal limits flickering without pretending a coarse fix
  establishes a room-sized boundary. Display accuracy/radius guidance.
- Recompute schedules on resume and time/timezone changes; while visible,
  schedule the next local boundary. Weekdays use the device's current timezone;
  evaluate wall-clock membership across DST changes, not fixed 24-hour offsets.
  No exact-alarm permission or background wakeup is needed for visible UI.
- Save rule definitions and user-named areas locally. Keep live fixes and signal
  history out of durable logs/analytics and backups; no movement history is
  needed. Saved place coordinates/network names are sensitive preferences: omit
  them from automatic backup in this first version and explain that migration
  requires recreating them. Logs use rule IDs/reason codes, not raw coordinates
  or SSIDs. An optional map fetch may reveal viewed regions to its tile provider;
  do not claim all map-editor traffic stays on device.
- Ask for permissions only when configuring/using the relevant optional rule,
  explain denial and provide a settings recovery action. No Usage Access,
  accessibility service, or notification listener is needed. Core organization
  remains usable without granting location or Bluetooth identity permissions.

Resource budgets above are testable proposed defaults, not measured battery
results. Verify callback cleanup, request counts and physical-device battery
behavior before release, including repeated HOME, screen-off, and many folders.
The first implementation must work without mandatory Google Play services;
Google's GeofencingClient is not that fallback.

Platform references: [AudioManager](https://developer.android.com/reference/android/media/AudioManager),
[AudioDeviceInfo](https://developer.android.com/reference/android/media/AudioDeviceInfo),
[location permissions](https://developer.android.com/develop/sensors-and-location/location/permissions),
[background location](https://developer.android.com/develop/sensors-and-location/location/background),
[geofencing](https://developer.android.com/develop/sensors-and-location/location/geofencing),
[WifiInfo](https://developer.android.com/reference/android/net/wifi/WifiInfo),
[Wi-Fi access rules](https://developer.android.com/develop/connectivity/wifi/wifi-scan),
and [alarm guidance](https://developer.android.com/develop/background-work/services/alarms).

### 6.3 Further visibility features worth considering

Recommendations, **not extra committed first-release scope**:

1. Charging state: useful for bedside/media folders, inexpensive local signal.
2. A specific connected Bluetooth device: car audio or work headphones; requires
   real connection state and potentially Nearby devices permission, not merely
   a paired-device name. Generic headset rules above need no named-device picker.
3. A timed manual override, such as Show for one hour then resume rules. The
   persistent explicit override above is already in the baseline proposal.

Keep calendar, weather, activity-recognition, other-app activity, and nested rule
automation out of initial Core scope; these introduce additional integrations,
permissions, or ongoing resource costs and are better candidates for later
providers/add-ons. Rules for shortcut visibility are distinct from automatically
populating folder contents; that latter feature has not been requested here.

## 7. Long-press organization picker — ORG-06

Keep the existing App info, Uninstall, Rename, Hide, and appropriate Pin actions.
Add the requested **Add to category** entry. It opens a chooser headed
**Add to category or folder**, with distinct Category and Folder sections and
Create category / Create folder actions.

- Show current memberships as selected. Multiple selections are allowed;
  Done commits the entire category/folder selection delta in one transaction.
  Cancel leaves state unchanged; failed saves never apply a partial selection.
- Category removal creates an Exclude override; a secondary Reset to automatic
  action can clear an override explicitly. Folder removal removes that member.
- Creation from this flow stages a new destination with the original app
  preselected. The outer Done commits new definitions, initial membership,
  placement and all other selection changes together. The outer Cancel discards
  staged definitions too, leaving no empty orphan. Standalone Settings editors
  save directly and atomically. If a destination disappears before Done,
  validation fails without partial writes and lets the user revise the selection.
- If category pages are disabled, explain that assignment is saved and pages
  can be enabled in Settings; assignment itself does not switch pages on.
- Folder creation asks for placement. If Most Used is off, App list remains
  available and the Most Used option explains how to enable it.
- Expose the same picker from Home, search, category pages, starred app rows,
  and folder contents. Dismiss the old sheet before opening an editor.
- Persistence failures keep the picker open with selections intact and Retry;
  do not show success or lose unrelated memberships.

## 8. Mega Sorter setup and operation — ORG-07

Start from Categories and folders Settings, a category's empty state, or a
category management action. Choose one or two distinct existing categories;
Create category is available inline. A one-category session has one active
direction (right/blue by default, changeable in setup); the unused side has no
glow and cannot assign. Disabled category pages can still be organized without
silently enabling them. Explain that their pages remain off.

Return to the main app list in explicit Mega Sorter mode:

- Right destination: faint blue edge glow, category name and right arrow.
- Left destination: faint green edge glow, category name and left arrow.
- X at the top center, below system insets, labeled Exit Mega Sorter.
- A compact Undo action and clear destination legend; color is not the only
  indication of membership or direction.

Show one entry per non-hidden app from the full catalog, regardless of normal
category Home suppression. Temporarily omit duplicate starred app entries and
folder shortcuts from this sorting canvas. Retain the populated alphabetical
rail, package/name search, and vertical scrolling. Normal page swipes are
disabled for the entire mode, including blank areas between rows.

A horizontal swipe to an active destination adds the app to that category and
leaves all other category memberships intact. An app already manually included
there is an idempotent no-op. An automatically included app can be made explicit
by that gesture, so subsequent metadata changes do not undo the manual choice.
The two destinations must differ. Existing members show their destination tint
on entry; an app in both shows distinct blue/right and green/left indicators,
not a blended unexplained third color.

Provide Assign to [category] actions for users who do not swipe. Ordinary tap
can still launch an app; long press retains the regular action sheet. Returning
from an app may resume the active sorter session, but a fresh HOME intent or
process restart exits to normal Home. Rotation preserves active destinations and
committed assignments, with interrupted visual animation settled to final state.

## 9. Sorter animation, gestures, and persistence — ORG-08

Each app row owns an independent gesture/animation state keyed by `AppKey`, not
its list position. The row's measured slot height remains constant for the
current font scale/viewport throughout the animation. Never remove the list item
or animate its layout height to zero.

Sequence on an accepted swipe:

1. Track horizontal movement after platform touch slop and clear horizontal
   dominance. Vertical gestures remain list scrolling/reachability. A canceled
   or insufficient swipe returns to rest without saving.
2. On release past the calibrated distance/velocity threshold, enqueue exactly
   one membership transaction and animate the row content out toward its target.
3. The content disappears inside the unchanged slot. A small centered dot appears.
4. After successful persistence, expand that dot into a low-opacity destination
   background and restore icon/text at their original positions. Keep membership
   tint/labels while Mega Sorter remains active.

Initial motion calibration target: approximately 160 ms outgoing and 200 ms
return, using translation, scale/clip and alpha rather than remeasuring list
layout. These are tuning targets, not values inferred from the screenshots.
Respect reduced/disabled system animations with immediate assignment feedback
and no fly-out. Text and icons must stay legible on wallpaper and all themes.

Other app rows can be swiped and the list can be scrolled while any animation or
save is active. Do not use a screen-wide busy flag, await an animation before
accepting another app, or serialize the entire UI behind DataStore writes.
Writes themselves remain atomic/ordered; only the same app's pending transaction
is protected against duplicate gestures. A row scrolled offscreen can settle its
visual state while its transaction finishes; recycling must not transfer color
or translation to another app.

Normal Home/category membership filters are frozen for the sorting canvas until
exit. App assignments do not remove or reorder rows. Keep the session's existing
row order; queue newly discovered entries for exit/explicit refresh. If an app
is removed or becomes unavailable mid-session, keep a disabled slot until the
session ends instead of launching a stale entry. Explicit Hide app remains
immediate for privacy and may remove that row; it is not an animation side effect.

If a destination category is deleted mid-session, immediately disable its side
and reject new assignments to that ID. Already queued writes revalidate the
destination at commit; they must fail visibly rather than recreate a deleted
category. Keep the other side usable, or exit if neither destination remains.
Disabling only a category's page does not invalidate its sorting destination.

On write failure, restore that row's prior membership display, show an actionable
error, and allow Retry. Other successful assignments remain saved. Undo restores
the precise previous override for the affected app/category, including Auto or
Exclude, without reverting other categories or subsequent edits. Retain a bounded
session undo history; each inverse checks the expected override and operation
token/version for that exact app/category pair, not the global document revision.
Sorting a different app must not invalidate Undo. A newer edit to the same pair
blocks an old inverse instead of overwriting that edit; a deleted destination
cannot be restored through Undo. Persisted success, not animation completion, defines
whether an action can be undone.

X/Back exit keeps all accepted assignments. Cancel only uncommitted drags and
visual animations; accepted queued writes finish through a store/controller
lifetime independent of the composable, with failures still surfaced. On exit,
reapply normal Home filtering and restore the nearest usable scroll anchor.

Gesture priority is explicit: Android system-edge navigation, modal popup/sheet,
alphabet-rail interaction, row sorter gesture, then ordinary page swipe. Vertical
list movement stays available when horizontal intent has not been established.
Do not intercept the screen's system Back edge or make the alphabet rail assign
the app behind it. No page swipe fires from a sorter gesture.

## 10. Visual reference study and native design

References: user screenshots **25634** (closed folder shortcuts) and **25636**
(open popup), attributed by the user to Niagara Launcher. They are reference
material, not app assets, and must not be bundled with Modular.

- **Closed state:** a named Folders section below the app alphabet; ordinary
  icon/name shortcut rows continue the existing column alignment.
- **Open state:** a compact rounded dark panel overlays the list, with one
  folder title and a roomy single-column icon/name list. The surrounding Home
  remains visible and provides context.
- **Type:** regular upright sans-serif app labels, stronger folder/section
  headings. The exact font, density, corner radius and animation cannot be
  determined from the static captures.
- **Surface:** the reference uses a cool dark popup against purple wallpaper.
  Modular uses its own Light/Dark/AMOLED surfaces, text and scrim roles; purple
  wallpaper is not a new required theme. Blue/right and green/left are scoped
  sorter feedback requested by the user, not a global color redesign.
- **Existing system:** preserve native Compose/Material 3, platform sans-serif,
  existing 18sp app labels, 40dp icons and minimum 60dp app-row sizing as the
  starting tokens (`ui/HomeApps.kt`); popup width and large-font layouts need
  device calibration. Use named semantic motion/color tokens centrally.

Do not carry over Niagara branding, its paid-feature entries, screen-time UI,
wallpaper assets, or decorative effects unrelated to these functions. Use real
window backgrounds and a single deliberate scrim; no transparent-window changes
that reproduce the earlier wallpaper ghosting bug. The sorter animation is
specified by the user's description, not observed in these static screenshots.

## 11. Acceptance criteria

| ID | Required evidence before calling the feature delivered |
| --- | --- |
| ORG-A01 | Clean install with categories off retains current Home; enabling each default/custom category creates exactly one page; disabling restores normal Home visibility without losing membership |
| ORG-A02 | Metadata fixtures cover every supported/missing/unknown category, deprecated game fallback, stale cache and package update; no Usage Access or network required |
| ORG-A03 | Include/Exclude/Auto precedence survives refresh/restart; app belongs to Work and Productivity simultaneously; removal is not reversed by auto matching |
| ORG-A04 | Any-hide-wins Home filtering handles multi-membership, pins and suggestions; disabling/deleting the last suppressing category restores the app; explicit Hide still wins everywhere |
| ORG-A05 | Home/global search finds category-only apps by label and package, once per identity, and excludes explicitly hidden apps; launching feeds local Most Used |
| ORG-A06 | Tile reordering across Home changes physical sides and distance; page state survives Settings/recreation; no wrapping; HOME resets to Home; disabled/deleted current page recovers |
| ORG-A07 | Sparse category rail includes only populated locale letters; category empty state works; vertical scroll, reachability and page gestures do not steal one another |
| ORG-A08 | Folder popup opens from both placements, stays within safe bounds, scrolls independently, launches the correct app, closes on outside tap/Back, and preserves the underlying anchor |
| ORG-A09 | Folder membership never removes standalone entries; folder move/rename/reorder/delete works; Most Used-off placement is explained; app-pin migration preserves exact order and identity |
| ORG-A10 | Long-press picker works on every app surface; multi-select, create category/folder, Cancel, failed save and Retry preserve consistent state without orphan collections |
| ORG-A11 | One/two-target sorter handles unused direction, existing/both memberships, duplicate swipes, manual/auto precedence, X/Back and durable accepted writes |
| ORG-A12 | While row A animates, swipe row B and scroll vertically; slot bounds/neighbor positions remain unchanged by animation; offscreen/recycled rows show the correct final state |
| ORG-A13 | Fault-injected slow/failed writes, overlapping different-app commands and Undo never lose unrelated memberships or claim unpersisted success |
| ORG-A14 | Rotation/process death, app uninstall/update, explicit hiding, and category deletion during a session have deterministic recovery without invalid launches |
| ORG-A15 | Native captures at 320/375/414/768dp, Light/Dark/AMOLED, wallpaper on/off, enlarged text, RTL and reduced motion; controls reachable, readable labels beyond color, basic non-swipe assignment/reorder actions |
| ORG-A16 | Existing Home/search/Most Used/hidden-app/rename/package-cache/module-failure tests still pass; category/page/learning state never depends on external add-ons |
| ORG-A17 | Every folder remains editable/previewable in Settings when manually hidden, unmatched, empty, permission-blocked, or placed in disabled Most Used; visibility never changes members, stored pin order, or app search |
| ORG-A18 | Headset connect/disconnect, overnight/weekday/DST schedules, connected Wi-Fi without internet, and saved map areas produce correct conditions; Any is default, All works, Unknown truth tables and fallback are covered |
| ORG-A19 | Denied/revoked permissions, Location off, unknown SSID, no provider, timeout, stale/inaccurate/boundary fixes and map failure preserve definitions and provide recovery; no automatic permission launch or false arrival/exit claim |
| ORG-A20 | Shared coordinator respects configured freshness/timeout/rate bounds for many folders; leaving launcher/screen-off cancels requests/listeners; no Wi-Fi scans, background tracking, exact alarms, or per-folder polling; validate on Samsung and without GMS |
| ORG-A21 | Visibility changes during pressing, scrolling, popup use, recreation and Most Used toggles preserve interaction/scroll anchors; no shortcut disappears under an active press and no popup closes on signal change |
| ORG-A22 | Map center/radius editing and numeric fallback save atomically; cancel preserves old area; privacy tests exclude raw location/SSID from logs, live state from storage, and sensitive rules/places from automatic backup; provider terms/attribution reviewed before map integration |

Automated tests and screenshot captures must be tied to the eventual code
revision. Physical-device checking includes the user's Samsung and its system
Back gestures. See the acceptance record for actual test and rendered-UI evidence;
the presence of a requirement here is not by itself a passing result.
