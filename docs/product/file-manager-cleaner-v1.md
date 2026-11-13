# Files and cleaner module v1

Status: **Accepted; implementation authorized by the user on 2026-11-10.**
Date: 2026-11-10. Reviewed Core baseline: `f19a5c5cf45eaab868e3148828776390dabf02f7`.
Working name: **Modular Files**.

## 1. Confirmed scope

- A separately installed module adds a page to the horizontal Home workspace.
- File management and cleanup belong to one module, not separate APKs.
- Version 1 covers phone shared storage, SD cards, and USB storage supported by
  Android/the device. Network storage comes later.
- Cleanup scans start manually. Scheduling comes later.
- Home Assistant dashboard and Proxmox pages are separate future modules.
- Settings chooses the Files page's placement. Leaving the page preserves the
  location and browsing position for the next visit.
- Each opened folder shows its size at the top left and its percentage of the
  storage volume's total capacity.
- Each storage root offers a WizTree/WinDirStat-style storage visualization.
- Each folder has a Clean action with standard cleanup recommendations,
  numbered-copy detection, and downloaded-version/revision detection.
- Numbered copies recommend keeping the latest. Conflicting highest-number and
  last-modified evidence requires a keeper choice. The retained numbered copy
  is renamed to the unnumbered name after the other selected copies are removed.
- Folder search offers Delete all results. Cleanup and search deletion both
  require a scrollable review of every affected file, showing size, modification
  date, and creation date when available, with Delete and Cancel actions.
- Normal deletion moves files to trash. A visible trashcan is grey when empty,
  blue for 1–20 files, and red above 20 files. Opening it offers empty and restore.
- Normal operation does not require All files access; an optional full-storage
  access mode expands coverage within Android's actual limits.
- Include an integrated Markdown viewer/editor, PDF viewer, archive extraction
  with an option to remove the archive afterward, and copy/move/cut operations.

The user accepted this specification, including the proposed defaults and final
recommendations. Implementation and verification status is tracked separately in
[the validation ledger](../implementation/files-addon-validation.md). An accepted
requirement is not evidence that its implementation has passed validation.

## 2. Home integration and presentation — FILES-01

- Enable the module, choose Add to Home screens, and place its page on either
  side of Home using the workspace screen organizer. No instance JSON editing.
- One Files page per module in v1; multiple independently configured pages are
  deferred. Disabling/removing the page keeps module settings and user files.
- The page opens to the file browser. Every folder exposes **Clean**, scoped to
  that folder. Cleanup results are an internal view with a return action to the
  same folder; separate Files/Clean tabs are not required. Horizontal swipes
  belong to workspace navigation, not an inner tab pager.
- The browser uses a persistent search bar, a plus menu for new folders or text
  files, and a top circular three-dot menu for Clean, Storage map, and other
  actions. Folder rows use long press for their actions and appearance.
  Put longer explanations in **Info** so the browser opens with concise guidance.
- Selecting a folder gives visual feedback as its icon moves into the folder
  header. The header identifies the selected folder while browsing its contents.
  Folder icons offer folder, game, audio, and music choices; a user can select
  a color or leave it on **Auto**. Auto uses a light yellow folder in Light mode
  and a dark blue folder in Dark/AMOLED mode. Persist icon and color choices
  across page changes and process recreation.
- **Clean** opens check choices for the current folder; **Start scan** starts
  analysis. Reopening Clean during a scan returns to its current progress and
  findings. Closing the sheet leaves the scan running and returns to browsing.
- Files opens the last valid location, with breadcrumbs, local file search,
  sort controls, and compact/details views. Persist the storage identity, folder
  URI/path, scroll anchor/offset, view/sort preferences, and active search state
  across page changes and process recreation. Persistence does not require an
  offscreen view or storage connection to remain active.
- A missing drive or revoked grant shows a reconnect state/storage overview and
  retains the remembered destination. Never silently switch to a similarly named
  folder on another drive. Destructive confirmations are not automatically
  replayed when restoring browsing state.
- The overview shows available storage locations, their known free/used space,
  favorites, and recent files. Unknown capacity remains unknown, not zero.
- Use Core's Light, Dark, AMOLED, typography, and settings design. Reserve the
  launcher rail/control area so files never draw behind navigation controls.
- File search stays within the selected location/subtree unless the user selects
  All connected locations. Core's bottom app search continues searching apps;
  these are distinct, clearly labeled scopes.
- Main Home keeps its alphabet rail. Secondary Home screens omit that rail to
  leave their content unobstructed. The direct Apps action and bottom app search
  remain available on every Home screen. Opening the drawer starts at Most Used,
  or the requested letter when using Main Home's rail.
- Vertical gestures scroll the file/results list first; reaching its bottom can
  open the drawer using the existing Home transition. Selection, dialogs, and
  other active interactions own their gestures before launcher navigation.
- Back dismisses selection/dialogs, then goes up a folder, then returns to main
  Home from the storage overview. Android HOME always returns to main Home.
- Module settings are reachable both from its page and Core's module settings.

### Folder size and storage map — FILES-01A

- At the top left of an opened folder, show its recursive file size and
  `folder bytes / total capacity of its storage volume × 100`. For example,
  `12.8 GB · 10% of 128 GB`. The denominator is total capacity, not used space.
- A folder on SD/USB uses that volume's capacity, not the phone's capacity.
  When the provider does not expose capacity, show the known size and
  `Storage capacity unavailable`; never substitute an invented percentage.
- Calculate off the UI thread, use cached results immediately when available,
  and label stale/calculating/partial results. Inaccessible descendants must
  produce an incomplete-coverage indication, not a falsely exact folder total.
- Size means logical file bytes unless the adapter can report allocated bytes;
  do not equate a logical folder total with guaranteed reclaimable disk space.
- Each storage root has a **Storage map** button. In selected-folder mode, the
  granted root can offer the same visualization, explicitly limited to that
  granted tree rather than presented as a scan of the whole volume.
- The map uses a zoomable/drill-down treemap: larger rectangles represent more
  bytes, folders contain descendants, and colors identify file types with a
  legend. Selecting a block shows name, path, size, and an Open folder action.
  Include breadcrumbs, a largest-items list, and a return to the browser.
- Keep volume capacity/free space distinct from scanned-file coverage. Show
  inaccessible or unscanned used space separately when it can be determined;
  unknown coverage remains unknown. Do not paint unscanned space as free space.
- Build the map progressively after an explicit request, with progress and
  cancellation. Retain valid cached analysis; do not rescan all storage merely
  because the user switches Home pages. Folder-size calculation is separate
  from a user-requested cleanup scan and cannot select deletion candidates.

## 3. Storage and access — FILES-02

- Permission onboarding belongs to the Files APK; Core does not gain broad
  storage permission merely because this module is installed.
- Provide a usable selected-folder mode using Android's system picker and
  persisted grants where supported. Show a Manage locations entry and handle
  revoked grants by asking to reconnect that location.
- Optional **All files access** supports broad shared-storage
  browsing/cleanup. Explain its scope before opening Android's permission
  settings. Declining leaves selected-folder mode usable.
- Label this setting **Full storage access** with an explanation that Android
  calls it All files access and still excludes protected application data.
  Offer it in module Settings and relevant access-limited views; never require
  it merely to open the page, view a granted document, or edit a granted file.
- Picker restrictions apply: do not promise that a user can select the phone's
  storage root or Downloads root as a directory tree, or browse other apps'
  protected directories. Offer only access routes actually supported by Android.
- Support mounted SD/USB storage through supported platform APIs/providers.
  Read-only media stays browsable; unsupported write actions explain why.
  Do not promise support for every filesystem, peripheral, or document provider.
- Never describe All files access as access to private application data.
  No root, Accessibility automation, Shizuku, or permission bypass is required.
- Google Play distribution with All files access requires an eligible file
  manager use case, a permission declaration, and approval. Approval and OEM
  permission behavior are not guaranteed by the specification.

## 4. File management — FILES-03

- Browse folders; sort by name, modification date, size, and type; optionally
  show hidden files. Remember view/sort preferences.
- Search filenames and folder names, with type, date, and size filters. Content
  indexing/OCR is deferred. Search reports which locations were included.
- Folder search exposes an Include subfolders toggle (proposed default: on),
  with the scope visible. It never silently searches another storage location.
- **Delete all results** prepares a review of all matches in the chosen scope,
  not only the loaded/visible result page. Finish enumeration before enabling
  final deletion, freeze the result set, and use the shared review in FILES-04C.
  Files created or newly matching after review are not added automatically.
- Open files in compatible apps, share, rename, copy, cut, paste, move, create folders,
  inspect properties, and delete. Support multi-selection and favorites.
- Provide thumbnails where practical and bounded; use external viewers for
  formats without a built-in preview. Markdown viewing/editing and PDF viewing
  are built in as specified below; other document editors are deferred.
- Copy stages references for Paste; Cut stages a move without removing anything
  from the source. Show the staged item count and Copy/Cut mode and allow Cancel.
  Move offers destination selection directly and uses the same operation engine.
  Proposed scope: this is the module's file clipboard, not an unrestricted
  system clipboard containing file contents or long-lived permission grants.
- Before Paste, validate the source, destination, permissions, and remaining
  space when known. Copy may be pasted again; completed Cut items leave the
  pending move list. Failed or unattempted items remain distinguishable for retry.
- Resolve name collisions explicitly: skip, keep both, or replace after review.
  Never silently overwrite existing content.
- For cross-location moves, copy and verify successful completion before
  deleting the source. Interrupted/failed copies retain the source and identify
  partial destinations. Provider limitations must not be reported as success.
- Operations show progress, cancel where safe, and report per-item outcomes.
  Process death, storage removal, and revoked permission must not silently
  duplicate work or lose source files. Interrupted destructive work requires
  reconciliation/review rather than automatic replay.

### Markdown viewer and editor — FILES-03A

- Open Markdown files inside the Files page with **Preview** and **Edit** modes.
  Proposed baseline: headings, lists/task lists, links, tables, fenced code,
  block quotes, and local images that the module has permission to read.
- Provide Save, Save as, Undo/Redo, text search, and a visible unsaved-changes
  indicator. Render untrusted content without script execution; remote images
  are not fetched automatically. External links open only on user action.
- Preserve supported encoding and line endings on save. Report unsupported
  encoding, read-only providers, oversized documents, or malformed content
  clearly instead of corrupting the file or blocking the launcher.
- Proposed recovery default: retain an app-private draft across leaving the
  page/process recreation without silently overwriting the original file.
  Closing the document offers Save, Discard changes, or Cancel. Explicit Android
  HOME still returns Home and retains the draft rather than trapping navigation.
- Detect an externally modified original before saving. Offer review/reload or
  Save as a copy; do not overwrite external edits silently. A failed save keeps
  the draft available. Use safe replacement where the storage provider supports
  it and define the weaker provider behavior before enabling in-place editing.

### PDF viewer — FILES-03B

- View PDFs inside the Files page. Proposed baseline: scrolling, pinch zoom,
  page count, jump to page, selectable/searchable text where present, and
  remembered page/zoom for the same unchanged document.
- Support password entry for supported encrypted PDFs; do not store passwords
  by default or include them in logs. Clearly report unsupported encryption,
  damaged files, and render failures with an Open with alternative.
- Render pages on demand with bounded memory and stop offscreen rendering.
  Scanned/image-only PDFs may have no searchable text; OCR, annotations, forms,
  signatures, and PDF editing are deferred unless separately requested.

### Archive extraction — FILES-03C

- Offer **Extract** with a destination picker, proposed default of a new folder
  named after the archive, estimated output size when known, collision choices,
  and a per-operation **Move archive to trash after successful extraction** option
  (proposed default: off). Archive creation/compression is separate future scope.
- Format support must be explicit. Proposed v1 set: ZIP, 7z, RAR extraction,
  TAR and TAR.GZ; confirm supported encryption and multipart variants before
  committing these as implementation acceptance requirements. Unsupported
  variants show a reason rather than claiming extraction succeeded.
- Show progress, cancellation, per-entry failures, and the output location.
  Check available space and expansion/entry limits; prevent archive paths or
  links from escaping the chosen destination. Never silently overwrite files.
- Retain the original archive if extraction is canceled, incomplete, corrupt,
  blocked by permissions/space, or has skipped/failed entries. Verify successful
  output and available integrity checks before offering/completing archive cleanup.
- The optional archive-removal plan is shown before extraction with the source
  archive's path, size, and available dates. After complete successful extraction,
  move that same unchanged archive to trash; never permanently delete it.
  If source identity changed or trash is unavailable, retain it and explain why.
- Do not remove output that existed before extraction on cancellation. Identify
  newly created partial output and offer a reviewed cleanup of those entries.
  Extraction and archive cleanup have separate recorded outcomes so retries
  cannot discard the only intact copy or repeat a completed cleanup.

## 5. Cleaner — FILES-04

### Compact browser controls

- Keep a search bar visible, with an icon for adding a folder or an empty text
  file. New file prompts for a name and an extension, defaulting to `txt`, and
  must not overwrite an existing item.
- Place the page overflow menu at the top. Clean and Storage map belong in
  that menu, rather than large command tiles.
- Use densely spaced folder rows: icon on the left at 1.5 times the label's
  line height, with a single-line name on the right. Folder rows have no
  individual overflow button; long press opens their menu, including Cut,
  Copy, Move and Delete alongside existing properties and appearance actions.
- Offer compact and details views. Details keeps names left aligned and
  displays known sizes at the right. Unknown or partial folder sizes must be
  distinguished from complete zero-byte sizes; reuse cached subtree totals.

### Live manual scans

- A folder's **Clean** action opens scan options scoped to that folder, with an
  explicit Include subfolders control. Choose checks before pressing **Start scan**;
  merely opening Clean performs no scan. If a scan is already running, Clean
  reopens it and its captured scope. Findings appear progressively
  while enumeration and verification continue. Closing the sheet leaves the scan
  running; Cancel explicitly stops it. Background completion must not navigate
  away from the user's chosen screen. Completed results expose the existing
  full deletion review; provisional findings cannot execute a cleanup plan.
  Cleanup does not run merely on page entry, and Scan again explicitly starts
  a fresh scan after completion.
- Save the user's selected checks. Default to **Numbered copies** only.
  **Exact duplicates**, **Older builds and versions**, **Large files**, **Old
  downloads**, and **Downloaded installers and archives** are separate opt-in
  checks. Age/size/type alone never makes a file a default cleanup candidate.
  Large-file and download-age thresholds remain configurable. Label content
  comparisons and APK build inspection as slower checks.
- Rank live and completed findings by evidence priority, then known reclaimable
  bytes descending within the same priority: verified identical copies first,
  numbered-copy/older-version groups next, then optional large-file, archive,
  and age-only findings. Unknown sizes stay unknown, and estimates exclude the
  retained copy. Priority helps order review; it does not authorize deletion.
- Use metadata for the initial filename scan. Do not hash every file merely to
  list recommendations. Only selected checks may read contents while scanning.
  With subfolders off, enumerate only the chosen folder. If a directory-copy
  group is selected for review, enumerate just that group's folders at review
  time, including the retained folder. Unknown folder sizes remain unavailable
  until those contents are enumerated.
  Explicit **Review selected** verifies fingerprints of the selected manifest,
  including keepers and selected directory descendants, before presenting the
  deletion review. Deletion still revalidates that manifest and directory child
  sets, including every kept directory's descendants. A changed or missing
  kept file blocks deletion of its other copy. Unrelated file contents do not
  need to be hashed for that review.
- Exact duplicates must be confirmed by content, not just names or sizes. Use staged
  comparison to avoid reading unrelated files; confirm equality before offering
  removal, retain at least one copy, and revalidate changed files before acting.
  Similar photos and semantic duplicates are deferred.
- Name/version-based recommendations do not claim byte-for-byte equality.
  A numbered folder may contain unique files; its contents are included in the
  deletion review rather than silently assumed to match the retained folder.
- Each result shows name, location, size when known, selection state, and why it
  was suggested. Findings are unselected by default, including duplicate groups;
  a suggested keeper does not automatically include its group for cleanup.
  **Review** on a finding prepares only that finding, asking which copy to keep
  when needed. **Include** builds an explicit batch, **Clear selection** resets
  it, and **Review selected** is disabled for an empty selection.
- Selection conflicts, occupied rename destinations, and incomplete verification
  stay on the findings screen with the affected names and a reason. A compact
  message can open a scrollable **Show issues** dialog. Never present internal
  IDs or fingerprint-preparation markers as instructions to the user, and never
  open the deletion review until its manifest is fully verified. Check actual
  rename destinations, including excluded files, before reading file contents
  for a review that cannot proceed.
- Support excluded folders/files and protected locations that scans cannot
  select. Exclusions apply to future scans and are editable in module settings.
- Review selected files and the action before cleanup. No automatic deletion,
  one-tap deletion of all suggestions, RAM boosting, or unsupported speed claims.
- Never claim to clear every app's cache. Private app caches are outside normal
  module access. Offer Android/app storage settings where useful; the user
  completes those actions there. The module can manage its own cache.
- Distinguish suggested bytes, bytes moved to trash, and bytes actually deleted.
  Never count the retained duplicate copy or overlapping results twice. Do not
  present moving files to same-volume trash as freeing their storage.

### Numbered files and folders — FILES-04A

- Recognize terminal copy suffixes such as `foldername`, `foldername(1)`,
  `foldername(18)`, and the equivalent space-before-parenthesis forms. For files,
  recognize a suffix before the extension: `filename(12).pdf` maps to
  `filename.pdf`; preserve complete known compound extensions such as `.tar.gz`.
- Compare sibling entries of the same kind and base name within the same parent.
  Do not group unrelated locations or treat every embedded number as a copy
  suffix. A recursive scan finds separate groups in each parent folder.
- Include the unnumbered original, if present, in timestamp comparisons. Numbers
  are numeric: `(28)` ranks above `(9)`, regardless of lexicographic order.
- If the highest-numbered copy is also uniquely newest by known last-modified
  time, recommend retaining it and removing the other group members.
- If the highest number and newest timestamp disagree, show both candidates
  and their evidence and require **Choose which to keep**. For example, when
  `(28)` has the highest suffix but `(12)` was modified last, preselect neither.
  Equal or missing timestamps and uncertain grouping also require a choice.
- Users may override any recommendation. Modification time is evidence, not a
  guarantee of the latest contents; folder modification time does not necessarily
  reflect changes inside descendant files. Do not invent creation times as a
  substitute for missing modification times.
- The plan explicitly shows the keeper, every removal, and the final rename.
  Keeping `foldername(28)` means retaining that folder, removing the other
  reviewed copies, then renaming it to `foldername`. Files follow the same rule:
  `filename(12).pdf` becomes `filename.pdf`. An unnumbered keeper needs no rename.
- Never delete the keeper. If the original base name exists, it must be included
  in the reviewed removals or the group remains unresolved. If removal fails or
  a new name collision appears, keep the retained item under its existing name
  and report the incomplete rename; never overwrite an unreviewed entry.
- Record removals and rename as one user-reviewed operation plan, with per-item
  results and recovery information. Do not claim filesystem-wide atomicity.

### Downloaded revisions and builds — FILES-04B

- Identify versions of the same downloadable product and recommend keeping the
  newest comparable version, e.g. `bookwave-0.9.7.apk` over `bookwave-0.8.1.apk`.
- Compare numeric version components, not filenames as strings: `0.10.0` is newer
  than `0.9.7`. Recognized prerelease/build formats use their defined ordering;
  opaque revision labels or incomparable formats require a keeper choice.
- For readable APKs, use package identity, signing identity when available,
  version name, and version code as evidence. Android version code determines
  update order within a compatible package/signing lineage. If that conflicts
  with a displayed/filename version, explain the conflict and ask which to keep.
- Keep release/debug, distinct packages, architectures, channels, and split APK
  sets separate unless compatibility is established. A higher version of a
  different variant is not a replacement for another required download.
- For other downloads, use a conservative product/base-name, extension, variant,
  and parsed-version grouping. Display inferred grouping and allow corrections;
  similar text alone is not sufficient to claim the same product.
- A newer version with an older modification time remains a newer revision;
  display both version and date so the recommendation is understandable.
- Keep the surviving versioned filename, including its version. Only a terminal
  numbered-copy suffix is removed by FILES-04A; do not rename
  `bookwave-0.9.7.apk` to `bookwave.apk` merely because older releases were removed.
- Resolve overlapping exact-duplicate, numbered-copy, and revision results into
  one plan. Deduplicate removals and ensure no rule removes another rule's keeper.

### Shared deletion review — FILES-04C

- Cleanup and Delete all search results use the same confirmation view: title,
  scope/query, total file count, known total size, and a scrollable file list,
  with persistent **Delete** and **Cancel** buttons. Preparing or canceling this
  view changes nothing on disk.
- Each affected file shows its name, distinguishing relative/full path, size,
  last-modified date/time, and created date/time. Show **Unavailable** when a
  provider/filesystem does not expose creation time; do not display the indexed,
  download, or modified timestamp under a Created label.
- Folder removal expands the operation to its descendants, including files that
  did not themselves match a search query. Every affected file must be reviewable
  in the scrollable, virtualized hierarchy; a top-level folder count alone is
  insufficient. Deduplicate files matched both directly and through a parent.
- Disclose incomplete enumeration, inaccessible descendants, and unknown sizes.
  A fully reviewed deletion set must be established before Delete is enabled;
  unresolved groups can be excluded so other fully enumerated groups proceed.
- Show retained items and pending renames alongside cleanup removals, including
  source and destination names. The confirmation explicitly says **Move to trash**;
  its requested **Delete** button performs that recoverable action. Permanent
  removal is a separate Empty trash confirmation, never an automatic fallback.
- Confirmation applies to the captured identities and versions of those files,
  not a query rerun. Revalidate before action: new/changed descendants, replaced
  entries, changed keeper evidence, or name collisions require fresh review for
  the affected group. Never recursively delete newly added, unreviewed children.
- Execute against reviewed child identities, not a blind recursive parent-delete
  call. Where a provider cannot support the required checks/selective operations,
  disclose that limitation and do not offer that destructive folder operation.
- Deleting search matches does not automatically delete now-empty parent folders
  unless those folders were explicitly part of the reviewed plan.
- Completion reports actual removals, kept items, successful/failed renames,
  skipped/changed entries, and recoverability. Repeated callbacks or process
  recreation must not replay deletion or discard the retained copy.

## 6. Trash, recovery, and history — FILES-05

- Browser deletion, cleanup, search-result deletion, and optional archive removal
  all move content to trash. Define reliable trash support per storage adapter:
  use provider trash or a documented same-storage implementation. Android does
  not provide one universal trash API for every file/provider.
- If recoverable trash is unavailable or moving to it fails, keep the original
  and explain the limitation. Never silently fall back to permanent deletion.
- Keep a visible trashcan in the Files page toolbar, including folder/root views
  and the storage map. It remains tappable when empty and opens the Trash view.
  Document viewers may expose it through their normal page toolbar.
- Indicator: **grey = empty**, **blue = 1–20 trashed files**, **red = 21 or more**.
  Include a numeric badge/label so the state does not depend solely on color.
  Proposed count rule: count files inside trashed folders, not each folder as
  only one file. A trash containing only empty folders is nonempty/blue and
  reports its folder count explicitly. Exactly 20 files stays blue.
- The indicator covers this module's trash across its managed locations, not
  an invented count of all Android apps' trash. Use a durable trash index; update
  counts after successful operations and reconcile changes. Retain last-known
  records for disconnected drives, label them unavailable, and never treat a
  missing drive as emptied trash.
- Trash lists name, original location, size, available original dates, and
  deletion date. Support multi-selection, **Restore selected**, **Restore all**,
  and **Empty trash**; empty/restore actions are disabled when nothing applies.
  Show the total known trash size beside its count in the Trash summary; the
  requested icon colors remain count-based even for one very large file.
- Restore to the original location by default, preserving supported metadata.
  For an existing name, offer Keep both, choose another destination, or Cancel;
  replacing an existing file requires a separate explicit reviewed choice.
  Missing/read-only destinations prompt for a writable location or reconnect.
  Failed restores remain in trash with actionable errors.
- **Empty trash** presents a scrollable list and file count/size and clearly
  confirms permanent removal. Remove only reviewed entries, retain failures,
  and report offline-drive entries as pending/unavailable rather than erased.
- Proposed default: no automatic trash expiry in v1. Trashed bytes still occupy
  storage until permanently removed. Exclude module trash from ordinary cleaner,
  search, and duplicate candidates and show it separately in storage analysis.
- Trash data must not rely solely on uninstall-cleared private storage. Specify
  same-storage recovery metadata and a rediscovery/reconnect path where feasible;
  explain provider/uninstall limitations. Uninstalling is not an Empty trash action.
- Keep a local operation history with successes, skipped items, failures, and
  retry actions. Logs omit file contents and credentials; detailed path sharing
  is opt-in. Clearing history never deletes user files.

## 7. Performance and privacy — FILES-06

- Cache directory metadata and thumbnails for quick reopening, mark stale data,
  and reconcile changed locations. Use available change signals plus targeted
  refresh; SAF/provider coverage varies, so keep manual Refresh available.
- Revalidate existence, permissions, and identity before changing/deleting a
  cached item. An inaccessible source does not mean its files were deleted.
- Load lists progressively; perform traversal, copying, and hashing off the UI
  thread with bounded concurrency. Do not rescan whole storage on each swipe.
- Hidden pages stop rendering work and unnecessary observation. User-started
  operations may continue through supported Android job/service mechanisms,
  with visible progress and cancellation; there is no idle background scanner.
- File metadata, hashes, and history remain on-device. No account, analytics,
  remote classification, or upload is required for v1. A local-only module should
  not request network access merely for a future network-storage feature.

## 8. Framework dependency — FILES-07

Existing Core constraints: protocol-v4 module discovery, identity checks,
settings, and Binder services exist. A working external Home page renderer does
not. See [Home workspace WORKSPACE-07](home-workspace-v1.md#8-future-add-on-pages--workspace-07)
and [the module model](../architecture/module-model.md#8-ui-capable-modules).

Specify and validate a versioned page capability before implementing this module:

- Core owns stable page identity, placement, enable/disable, theme, navigation,
  lifecycle, and recovery. It never loads arbitrary module Compose classes.
- A bounded declarative surface supports paginated lists/grids, breadcrumbs,
  selection, toolbars, forms, confirmations, progress, errors, and typed actions.
  Specify a bounded native treemap node with drill-down/selection semantics and
  incremental data; neither visualization nor deletion review sends an unlimited
  directory tree in one Binder payload.
- Include explicit host contracts for the Markdown editor and PDF viewer, with
  scoped document access, bounded text/page data, draft persistence, and lifecycle
  handling. A generic list-only page schema is insufficient for these requirements.
- Modules own file access, indexing, operation execution, and permission setup.
  The contract supplies revision-aware action IDs and cancellation; stale UI or
  repeated Binder calls cannot accidentally repeat destructive operations.
- Authenticate both endpoints using the existing package/signing/session model.
  Request only necessary Core capabilities. Stream file content through explicit,
  scoped URI/file-descriptor grants, not large Binder payloads or arbitrary paths.
- Permission/configuration activities are launched intentionally; the embedded
  page must not be simulated by unexpectedly launching a separate full-screen app.
- Handle loading, permission needed, missing drive, incompatible version,
  timeout, and module crash. Preserve usable main Home, drawer, and Settings.
- Reuse lifecycle/navigation/identity for future Home Assistant and Proxmox pages.
  A live Home Assistant dashboard needs a separate rendering/authentication design;
  this native file surface does not claim to embed arbitrary web dashboards.

## 9. Acceptance examples

1. Add and reorder the Files page, browse files, open the drawer, and return to
   the same valid folder without changing main Home or category pages.
2. Decline broad access, grant one folder, and successfully manage supported
   files there. Revoke that grant and get a reconnect state without data loss.
3. Browse supported SD/USB media; remove it during a copy. Source files survive,
   incomplete work is identified, and the launcher remains responsive.
4. Same-name different-content files are not called duplicates. Genuine copies
   are grouped; excluding a folder and preserving a selected copy are respected.
5. Scan results disclose their coverage. A skipped/denied source is not counted
   as empty or fully scanned. Cleanup acts only on reviewed selections.
6. Trash, restoration conflicts, permanent-delete confirmation, low space,
   cancellation, process death, and module removal have explicit outcomes.
7. Duplicate action delivery or an outdated scan cannot delete a replacement
   file. Cross-storage moves never delete the source after a failed copy.
8. Large directories, all three themes, larger fonts, and independent page/app
   search remain usable. Offscreen idle behavior shows no periodic full scan.
9. Leave a deeply scrolled folder, switch Home pages, recreate the process, and
   return to its saved folder and position. Disconnected storage offers reconnect
   rather than silently changing the remembered location.
10. Folder size uses the containing volume's total capacity as its denominator.
    Partial totals and unknown capacity are labeled; the root storage map's
    coverage never implies access to private/unscanned app data.
11. With `foldername` and copies `(1)` through `(28)`, recommend `(28)` only when
    its timestamp agrees. If `(12)` is modified last, require a keeper choice.
    Choosing `(28)` reviews the other folders' full contents and the rename to
    `foldername`; failures preserve the keeper and avoid unreviewed overwrites.
12. Apply the same rule to numbered files with extensions. Keep `0.9.7` over
    `0.8.1`, and `0.10.0` over `0.9.7`, while keeping incompatible build variants
    separate. Version/date conflicts are explained rather than silently resolved.
13. Delete all search results includes offscreen/paginated results and descendants
    of matching folders in the review. Cancel performs no operation. Unavailable
    Created dates remain explicitly unavailable.
14. After review, add a child to a selected folder, replace a matching file, or
    create the planned rename destination. The operation does not delete or
    overwrite the unreviewed data and requests refreshed review for that group.
15. Trash indicator transitions correctly at 0, 1, 20, and 21 files, including
    files inside folders. Restore/empty failures and unplugged storage do not
    falsely reduce the count or trigger permanent deletion as a fallback.
16. Restore selected/all entries with original-name conflicts; Cancel changes
    nothing. Empty trash reviews permanent deletion separately and records
    partial failures while preserving remaining entries.
17. Edit Markdown, leave the page, recreate the process, and recover the draft.
    A failed save or external modification never silently discards either copy.
18. View a large PDF without rendering every page in memory. Restore the reading
    position; searchable text works where available and unsupported encryption
    or image-only content is reported honestly.
19. Cut then Cancel leaves sources untouched; failed cross-storage Paste retains
    sources and identifies partial output. Copy remains distinct from Cut.
20. Successful extraction with source cleanup selected moves the archive to trash.
    Cancellation, wrong passwords, corruption, insufficient space, skipped items,
    changed source identity, or unsafe entry paths retain the original archive.

## 10. Decisions still open

- Exact archive formats, encrypted archives, and multipart archive support.
- Confirm proposed no-expiry trash policy, count semantics for folders, and
  recovery defaults for Markdown drafts and file-name conflicts.
- Optional convenience for review: Undo the last supported rename/move/trash
  operation from history, with conflict checks. Emptying trash cannot be undone.
- Final page-host wire/schema design and storage-provider compatibility matrix.

Deferred: network storage, scheduled scans, archive creation/compression, content
indexing/OCR, PDF editing/annotation/forms, similar-photo detection, backup/sync,
root features, and additional module page instances. These are not v1 acceptance
requirements. Search inside an opened Markdown document or text PDF is distinct
from indexing the contents of all storage files.

## Sources checked 2026-11-10

- [Android: All files access](https://developer.android.com/training/data-storage/manage-all-files)
- [Android: Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files)
- [Google Play: All files access policy](https://support.google.com/googleplay/android-developer/answer/10467955)

## 10. Launcher identity

The Files APK uses a recognizable folder icon with the existing Modular mark in
the lower-right corner. Supply adaptive and monochrome Android icons. The Files
name and package must remain distinct from Modular Core in Android Settings,
permission requests, installation screens, and CI artifacts.
