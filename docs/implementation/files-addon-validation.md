# Modular Files v1 validation

Status: acceptance plan; execution results are **pending**. Source of truth: [Files and cleaner module v1](../product/file-manager-cleaner-v1.md). This plan covers the whole accepted v1 scope. A passing unit test or API 35 emulator run is evidence for its stated case only; it does not imply that removable media, OEM behavior, or every document provider passed.

## Test boundaries and evidence

- **U**: deterministic JVM unit test with synthetic file identities, timestamps, provider failures, and fixed clocks. Use for grouping, version order, counts, protocol bounds, and operation planning.
- **I**: Android instrumentation against actual Core and a separately installed Files APK on the API 35 `google_apis` emulator. Cover discovery, authorization, Home placement, Binder delivery, SAF grants, UI state, and real file operations. Keep fixtures in an instrumentation-owned selected tree or a disposable public test directory. Remove only test-created files.
- **D**: device/manual acceptance, with recorded device, Android/One UI version, filesystem/provider, permission state, result, and evidence. Required for SD and USB unplug/reconnect, read-only volumes, Samsung permission UI, and provider-specific behaviors that the emulator cannot represent. Mark these **unverified**, never pass by inference.
- **S**: captured UI evidence from the installed APKs. Save only screenshots actually produced by a named instrumentation test; validate output files are nonempty. Avoid recording user filenames or content in shared artifacts.

The existing `.github/workflows/android.yml` uses API 35, installs release Core then debug Core, and runs cross-APK instrumentation. The Files APK must be installed independently before the Files tests; the root build owner wires its Gradle tasks, installation, and `scripts/capture-files.py` into CI. Tests should report exact class names and result counts, not infer success from APK assembly. The capture script runs a small theme/font matrix using `FilesCrossApkHomeTest` and checks each actual output file. No capture result exists until that script has run successfully.

## Traceability matrix

| Requirement | Observable acceptance check | Level |
| --- | --- | --- |
| FILES-01 module discovery, one page, enable/add/reorder/remove | Separate APK appears as a trusted page provider; Add to Home creates one stable page; reorder to either side survives Core recreation; disable/remove preserves module settings and fixture files; existing Home/category/drawer remain reachable. | I, S |
| FILES-01 browser navigation and state | Open a nested folder, scroll deeply, set sort/view/search, switch pages and recreate Core; same storage identity, URI, anchor/offset, preferences, search scope return. Missing drive/revoked grant retains destination and shows reconnect. No stale delete dialog returns. | I, D |
| FILES-01 overview/search/navigation | Overview presents known/unknown capacity, favorites, recent items; local subtree and all-connected search declare scope; app search remains separate; alphabet rail, direct Apps, Most Used/requested-letter drawer, vertical gesture priority, Back sequence, Android HOME, and both Settings entry points work. | I, S |
| FILES-01A folder size | Recursive logical bytes use the containing volume's **total** capacity; unknown capacity stays unknown; inaccessible child yields partial result; stale/calculating labels and off-UI-thread progress appear. | U, I, D |
| FILES-01A map | Explicit root/granted-tree request incrementally paints bounded treemap nodes, legend, breadcrumbs, largest items, detail/Open folder, progress/cancel; selected-tree coverage is labeled, unknown/unscanned space is never shown as free; switching Home pages does not restart a full scan. | U, I, S |
| FILES-02 selected-folder access | Decline Full storage access, grant tree through system picker, persist access through recreation, browse/edit supported files, revoke and reconnect; root/Downloads picker restrictions and protected data limits are explained. | I, D |
| FILES-02 optional broad/media access | Full storage access is opt-in with scope explanation, permission remains in Files APK, denial leaves selected mode usable; mounted SD/USB and read-only media have accurate browse/write outcomes; no private-data promise or bypass. | I, D |
| FILES-03 browsing/selection | Name/date/size/type sort, hidden toggle, list/grid persistence, search type/date/size filters and subfolder toggle, thumbnails, compatible Open/share, create/rename/properties, favorites, multi-select. | U, I, S |
| FILES-03 search Delete all | Enumerate offscreen results, all matching-folder descendants, and overlaps before review; freeze identities. Newly matching files after review remain untouched. Cancel leaves disk unchanged. | U, I |
| FILES-03 copy/cut/move | Copy/Cut staging count, mode and Cancel; source remains on Cut; destination validation, skip/keep-both/reviewed-replace, repeat Copy, per-item retry; failed/interrupted cross-volume copy never removes source, partial output is identified. | U, I, D |
| FILES-03A Markdown | Built-in Preview/Edit supports specified Markdown constructs and local permitted images; remote resources/scripts stay inert. Save/Save as/Undo/Redo/find/dirty marker, encoding/newline preservation, unsupported/read-only/oversize errors, app-private draft across HOME/process death, close choices, external-edit conflict and failed-save recovery. | U, I, S, D |
| FILES-03B PDF | Built-in on-demand scrolling/zoom/page jump, count, text search/selection where text exists, position restore for unchanged document, password prompt without persistence/logging, damaged/unsupported/image-only outcomes and Open with fallback; large file has bounded rendering. | I, S, D |
| FILES-03C archive | Explicit supported formats/variants, destination and collision options, size/entry/expansion limits, traversal/link containment, progress/cancel/partial output; cleanup plan names source; only complete verified extraction moves the same unchanged archive to trash; all failures retain source. | U, I, D |
| FILES-04 scan and recommendations | Clean is scoped to current folder with include-subfolders toggle; no scan on entry; last scan/coverage shown. Exact content duplicates differ from same-name/different-content; large/old/download/installers, numbered, revisions grouped with rationale, thresholds, exclusions, protected locations, selectable keeper and unselected large/old defaults. | U, I, S |
| FILES-04 accounting/privacy | Overlapping recommendations count once; suggested, moved-to-trash, actually deleted bytes differ; trash move is not claimed as freed space. Private app cache is not promised; own cache/settings action only. | U, I |
| FILES-04A numbered copies | Sibling-only same-kind grouping, optional space, extension and compound extension, numeric suffix order, original in timestamp comparison; `(28)` and newest agree for recommendation, disagreement/equal/missing requires chooser; override and full child review; remove then rename, with failure/collision retaining keeper. | U, I |
| FILES-04B downloaded versions | Numeric `0.10.0 > 0.9.7`, prerelease/build ordering, opaque chooser; APK package/signing/versionCode evidence and conflicts, variants/channels/architectures separate; versioned keeper keeps filename; overlap with other groups has one consistent keeper/removal plan. | U, I |
| FILES-04C shared review | Search and Clean show scope, full virtualized hierarchy, file count/known bytes, each path/size/modified/**real created or Unavailable**, retained items/rename, sticky Delete/Cancel and Move to trash wording. Incomplete groups cannot be confirmed. | U, I, S |
| FILES-04C action safety | Captured identities/versions, new child/replaced file/changed keeper/new destination collision trigger fresh review for affected group. Execute reviewed child IDs, not blind recursive deletion. Duplicate delivery and process recreation do not replay. Completion has per-item outcomes. | U, I, D |
| FILES-05 trash | All normal deletion routes use recoverable provider/same-volume trash. Unsupported/failure leaves source. Toolbar indicator visible at root/folder/map; 0 grey, 1–20 blue, 21+ red, numeric count, folder descendants and empty-folder semantics. Offline records remain counted/unavailable. | U, I, S, D |
| FILES-05 restore/empty/history | Restore selected/all to original or alternate with conflict choices and metadata where supported; Cancel inert; failures remain. Empty trash separately reviews permanent deletion, retains failures/offline entries. Uninstall is not Empty; no automatic expiry; rediscovery limitations stated; operation history/retry and clear-history safety. | U, I, D |
| FILES-06 performance/privacy | Progressive bounded traversal/list/thumbnails/hash/copy; cache stale/refresh, no offscreen idle full scan; module remains responsive during long work and cancellation; file metadata/history local, no network permission or background upload. | U, I, D |
| FILES-07 page contract/security | Versioned bounded page JSON/treemap/editor/PDF contract rejects oversized/malformed input; typed revision-aware actions and cancellation; signing/session authentication in both directions; scoped document grants; failure states and module crash preserve Core; no arbitrary Compose loading or unexpected full-screen launch. | U, I |

## Focused fixture scenarios

1. **Cross-APK Home and grant.** Install Files debug APK separately from Core. Inspect trusted route and package identity, add page through Settings, reorder, browse a test SAF tree, recreate process, then revoke grant and verify reconnect. Check module settings and user files survive page removal. A shell-created public folder can exercise broad-mode browsing, but a selected-folder test must use the actual system picker and persisted URI grant.
2. **Reviewed deletion and trash boundary.** Seed 21 tiny uniquely named files, one nested folder, and a same-name/different-content pair. Search with pagination, review all matches and descendants, cancel, repeat, confirm only captured identities, then check counts/colors at 0, 1, 20, 21 and after restore/empty failures. Inject a new child and replace a reviewed file between review and Delete; both must survive and require refreshed review. Include missing Created metadata and offline-index cases.
3. **Numbered/version choice.** Seed sibling `foldername`, `(1)`–`(28)` with `(12)` newest; assert no preselection and explicit keeper. Review descendants, then add a destination collision. Keeper remains under its original name. Separate file fixtures exercise `.tar.gz`, `0.10.0` vs `0.9.7`, and variant separation; do not require actual APK parsing from a text fixture.
4. **Documents and archive.** In the granted tree, edit UTF-8 Markdown with CRLF, retain draft across page/process recreation, change original externally before Save and verify no silent overwrite. Open a generated text PDF and image-only PDF, test page/search outcomes and bounded rendering. Extract a safe ZIP with optional trash cleanup, then a ZIP containing `../escape` and a corrupt ZIP; source survives unsafe/incomplete cases and no entry escapes destination. Track created partial output separately from preexisting files.
5. **Move and interruption.** Cut a source, cancel, then test cross-location copy failure with an adapter fault or removable media unplug; source remains, partial destination is marked, retry is explicit. An emulator fault-injection adapter can prove operation policy; physical unplug needs a device run.

## Gate and result ledger

| Gate | Current result | Evidence needed |
| --- | --- | --- |
| JVM protocol/planner/adapter tests | Tests added; execution pending | Exact test reports, source revision |
| API 35 direct-storage safety instrumentation | Test added; execution pending | `DirectStorageSafetyTest` connected XML, clean fixture teardown |
| API 35 actual SAF picker and grant | Test added; execution pending | `SelectedFolderSafTest` connected XML, real DocumentsUI grant/revoke |
| API 35 Markdown/PDF/archive files | Tests added; execution pending | `MarkdownRealDocumentTest`, `PdfRealDocumentTest`, `ArchiveRealStorageTest` connected XML |
| API 35 cross-APK Files instrumentation | Test added; execution pending | Separate APK install plus connected test XML |
| API 35 Files captures | Capture script added; execution pending | Actual nonempty PNGs and capture log |
| Samsung One UI selected-folder/full-access flows | Unverified | Device/version, permission path, outcomes |
| SD/USB unplug/read-only/provider matrix | Unverified | Device/media/provider, failure and recovery outcomes |

No requirement above is waived by a missing fixture. If automation cannot exercise a case, record it in the device ledger with a reproducible procedure and an unverified result until run.

## Device ledger template and required runs

For each run record: date, device/model, Android and One UI version, Files/Core APK version or source revision, storage/provider/filesystem, access mode, fixture IDs (never personal paths), observed result, screenshot/log reference, and tester. Keep personal files out of the test location.

| Run | Procedure | Pass condition | Current result |
| --- | --- | --- | --- |
| One UI selected tree | Decline Full storage access; select a test directory in Samsung's document picker; browse/edit a test Markdown file; force-stop/relaunch; revoke the tree grant; return to Files. | Grant persists before revocation; after revocation the remembered destination remains and reconnect is offered; Core Home/Settings remain responsive. | Unverified |
| One UI Full storage access | Enter module Settings; inspect scope text; decline, then grant through system Settings; compare accessible shared locations and protected app data. | Decline preserves selected mode; grant expands only allowed coverage; permission belongs to Files APK and no private-app-data claim appears. | Unverified |
| SD/USB disconnect | Browse test media, start a cross-location copy or move, unplug during progress, reconnect and inspect source, partial destination, history, and trash count. | Source survives incomplete copy; failed work is explicit; no duplicate or silent replay; offline trash records remain unavailable/countable; launcher is responsive. | Unverified |
| Read-only/provider capability | Open a read-only volume and one third-party document provider; attempt rename, trash, restore, extraction destination, and Markdown Save. | Browse/preview works where supported; unsupported writes explain limitation and preserve originals; no permanent-delete fallback. | Unverified |
| Large directory and document | Create a large test directory and PDF on a disposable location; scroll/search, switch Home pages, cancel map scan, inspect memory/responsiveness. | Lists and map page incrementally; offscreen idle does not scan; PDF pages render on demand; Home/drawer remain usable. | Unverified |

The ZIP path traversal case, changed-child review case, and numeric version ordering should also run on the emulator where deterministic fixture creation is possible. Manual runs complement those checks; they do not replace them.

## Open decision register

The product spec explicitly leaves these choices open. Record the implemented choice, compatibility limits, and evidence before claiming v1 acceptance; an absent implementation is not a pass.

| Decision | Required recorded outcome |
| --- | --- |
| Archive formats | Exact ZIP, 7z, RAR, TAR, TAR.GZ support; encrypted and multipart variants for each; unsupported variants' user-facing reason. |
| Trash policy | Expiry policy, count treatment for empty folders and nested files, same-storage/provider strategy, uninstall and reconnect recovery limitations. |
| Markdown recovery | Draft lifetime, encoding/newline support, safe-save behavior per provider, failed-save and external-change choices. |
| File collision defaults | Copy/move/restore/extraction choices and whether any reviewed Replace path is offered. |
| Page schema | Final native map/editor/PDF sub-schemas, payload and paging limits, scoped document transport, revision/token persistence. |
| Storage providers | Phone, SD, USB, SAF provider/read-only matrix, with limitations recorded per adapter. |
