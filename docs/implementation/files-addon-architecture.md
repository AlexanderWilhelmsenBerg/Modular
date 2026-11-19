# Modular Files implementation architecture

Baseline: Core `f19a5c5cf45eaab868e3148828776390dabf02f7`.
Scope: [accepted Files v1 specification](../product/file-manager-cleaner-v1.md).

## Files 0.1.8 / SDK 0.4.1: detached surfaces and log downloads

The 0.1.7 device report identified a main-thread NullPointerException in
`SurfaceControlViewHost.release()` called by the SDK after Android had already
released its ViewRoot. Core's existing detach notification can release the
platform host before the session-close Binder call arrives. Root detachment now
retires the provider session and records that Android owns host teardown.
Explicit cleanup clears owned references before callbacks and contains/logs
runtime teardown failures while still closing remaining resources. The public
surface API and Core caller contract are unchanged; Files bundles the SDK fix.

Log downloads capture and fsync JSON under private `files/event-log-exports`
before launching the create-document picker. A pending filename survives
Activity/process recreation; the result callback writes that prepared snapshot.
Only successful writes clear the pending download; cancellation clears the
pointer while keeping the recent private copies. The existing write/readback
check still detects an empty destination. Share JSON can read these files via a
specifically scoped FileProvider path. Export preparation, completion and errors
are included in subsequent event logs.

Regression coverage uses real SurfaceControlViewHost sessions and Android
Activity-result dispatch without Espresso: detach followed by repeated close
while a scan runs, a Files surface detaching during the event-log download
handoff, and cleanup after failed view creation. SDK unit tests inject the
reported release exception and verify that an earlier cleanup failure does not
skip remaining resources. Android 37 uses the explicit lifecycle-only device
suite because the existing Espresso UI harness calls a removed platform method;
the full Files suite remains on Android 35.

## Ownership and module boundaries

- `addon-files` owns the complete interface, user-granted storage access, scans,
  trash, archives, documents, and private browsing/history state.
- `module-api` supplies `modular.workspace.surface/v1`; `module-sdk` authenticates
  sessions and hosts the add-on's own view hierarchy. SurfacePackage handles
  cross Binder; executable add-on classes do not load into Core.
- `module-files-ui` is a private Files library shared by its Activity and native
  provider. Core has no production or test dependency on it.
- Core owns the generic page placement, native surface viewport, lifecycle,
  navigation, trust checks and unavailable-provider recovery.
- The old `modular.workspace.page/v1` endpoint remains in Files for older Core
  versions. New Core uses its native surface. After the one-time framework
  migration, Files UI updates require only a Files APK.

See [ADR-0009](../decisions/ADR-0009-independent-native-module-surfaces.md).

## Work allocation

Six bounded agents implement storage/trash, cleanup analysis, documents/archives,
page protocol/Core integration, Files UI/controller, and independent validation.
The coordinator owns Gradle and manifests, packaging/signing/CI, the folder icon,
integration review, serial builds, and the final pull request. Source ownership
is separated to reduce conflicting edits; shared API changes are coordinated
between owners before integration.

## Operation model

Deletion prepares a complete immutable descendant manifest before confirmation.
Execution rechecks identities and directory children, moves only reviewed entries
to recoverable trash, and records per-item outcomes. Process interruption never
automatically repeats a destructive confirmation. Source files remain intact when
copy or extraction is incomplete; archive cleanup is a distinct reviewed step.
Temporary access failure is not reported as permanent deletion or empty storage.

Long scans run away from Compose and Binder response paths. Progress and explicit
cancellation keep navigation responsive. Cached folder analysis is separate from
manual cleanup and cannot imply that cleanup candidates were selected.

Files 0.1.3 accepts commands on a controller-owned queue, so disposing a Compose
surface does not cancel an accepted click. Scans, review preparation, and storage
jobs hold a `dataSync` foreground service with an ongoing progress/cancel
notification and a bounded wake lock. Screen navigation never requests operation
cancellation. Android foreground-start failures are logged and displayed; OS
timeouts request cancellation. Process death is reported from a persisted running
marker on restart and never replays destructive work automatically.

Files 0.1.4 waits for the foreground service to acknowledge startup before storage
work begins. If Android rejects an embedded background start, the existing
authenticated Activity handoff retries from a visible Files Activity. It does not
silently execute without background protection. Cancellation records whether it
came from the screen, notification, or Android's data-sync timeout. Progress uses
an explicit Stop label; Back leaves the operation running.

Files 0.1.5 indexes each storage location's trash folder once per preparation or
execution pass. Up to four metadata workers read independent sidecars and prepare
independent payload identities; deletion remains serial under the mutation lock.
Progress reports index/review item counts, and exported diagnostics include worker
count, reviewed entries, manifest entries and preparation time.

Read-only Empty trash requests and prepared manifests are stored atomically in
private storage. After a process restart, pending preparation restarts and a ready
manifest reopens for explicit confirmation. The service requests intent redelivery
only while a pending read-only request exists. Confirmation is never persisted as
authorization to replay deletion; live identity/child checks still apply. Explicit
cancellation clears the pending request. Android's recent process-exit reasons are
recorded on startup and included in JSON exports so crashes, memory pressure and
other process stops can be distinguished. Home navigation does not request cancel.

Files 0.1.6 renders dropdown menus as bounded, scrollable overlays inside the
module's existing surface. Drawing, anchors and input use the same root; menus
create no separate Android popup window. Outside tap, Back and Escape dismiss
without dispatching a file action, and the module intercepts app navigation while
a menu is open. Folder context menus expose Copy, Cut, Move and reviewed Delete
without a separate selection step. Storage location roots remain excluded.
The visual dashboard audit is recorded in `docs/design/files-dashboard-review.md`.

Files 0.1.7 exposes Android notification permission/settings from active work and
Files settings. The foreground notification opens the current operation; a
separate completion notification remains after the service stops. Notification
permission is optional and never a substitute for the foreground-service gate.

Cleanup scan options, progress counts, a completed snapshot and final findings
are saved atomically. Pending read-only work can restart in the service after
process loss; stable request IDs survive restart. Completed findings restore
without another content scan and still require fresh review before deletion.
Explicit cancellation clears the saved request. Three failed automatic recoveries
pause further retries. Destructive confirmations are never persisted for replay.

Older-version scans use confined, granted local paths for APK metadata rather
than copying every local APK into cache. Providers without a local path retain
the bounded-buffer staging path and report its byte progress. APK evidence is
cached by file identity/revision and rechecked when the revision changes.

An uncaught-exception handler writes a bounded private crash report independently
of controller/event-log locks and then delegates to Android's original handler.
The report includes exception/root cause, stack, thread/process, operation step
and heap usage/limit. It does not swallow a fatal exception. The next startup
imports it into Event log; JSON and Copy crash include its separate full report.
The supplied 0.1.6 evidence establishes Java crashes during Older versions scans,
including foreground-service importance, but does not contain their exception
stacks. Hardware-specific resolution therefore remains unconfirmed until a stack
or a reproducing device test identifies the exception.

Progress publishes the current verification/mutation step, byte count when known,
and elapsed time, without taking the page lock from a repository callback. Page
rendering uses cached trash metadata; active mutations use display-only folder
snapshots. Reviewed execution validates only its manifest, reviewed directory
children, and rename destinations, then reuses that verified manifest for trash
instead of walking unrelated subtrees. File-only plans reuse verified hashes;
directory plans also pass the repository's provider-specific review gate. The
repository still checks identities and directory children before mutation.

Delete/trash and cleanup reviews use versioned identity/metadata revisions rather
than reading file contents. Direct storage includes device/inode, type/mode,
size, and nanosecond mtime/ctime. Provider storage uses its document identity and
available metadata. New children, replaced files, protected roots, and unsafe SAF
folder removal remain blocked. After removing reviewed children, directory
identity is rechecked independently from the metadata those removals change.
Content verification remains separate for exact-duplicate comparisons, copied
data, and document saves. A metadata identity is not a content-equality claim.

Numbered-copy suggestions prefer the highest number unless a known modification
date shows a lower-numbered entry is newer, or the highest number is ambiguous.
Equal or absent dates do not force a choice. Literal base names containing a year,
such as `guide (2017).pdf`, remain members of their numbered-copy family and keep
their year when renamed. Suggestions still require reviewed confirmation.

The Event log's Download JSON action opens Android's save-document picker and
exports every retained entry and detail, plus app/Android version and current
operation progress. The clipboard/share preview remains capped at 32,000
characters; JSON has no preview truncation. The bounded log retains at most 200
events / 192,000 encoded bytes. Neither export includes file contents or action
tokens. Exports are accepted by an application-owned queue, stage a complete
private JSON file before opening the destination, and check readback when the
provider permits it. Activity destruction cannot cancel the accepted write. Share
JSON grants read access only to the staged export, avoiding a destination-provider
write. JSON also includes retained operation-history outcomes for partial failures.

## Delivery and verification

Root coordinates serial unit, Android compilation, lint, and APK assembly checks.
CI installs Files as a separate APK before exercising Core page discovery and
native rendering. Signed distribution produces Core and Files debug/release APKs
with existing persistent variant certificates; signing material is only restored
inside GitHub Actions. Native screenshots cover Light, Dark, AMOLED, and larger
font presentation. Hardware/provider-specific checks remain explicitly recorded
as unverified until tested; see the validation ledger for exact evidence.

## Archive dependencies and compatibility

The archive reader is `me.zhanghai.android.libarchive:library:1.1.6`
([source](https://github.com/zhanghai/libarchive-android)), an Apache-2.0 Android
wrapper around BSD-licensed libarchive. It ships arm64-v8a, armeabi-v7a, x86, and
x86_64 native libraries. The 64-bit libraries in 1.1.6 use 16 KiB ELF load-segment
alignment for modern Android devices. No UnRAR-licensed dependency is used.

| Format | v1 extraction | Passwords / volumes |
| --- | --- | --- |
| ZIP | Single archive | Linked libarchive-supported encryption; split volumes unsupported |
| 7z | Single archive | Linked libarchive-supported encryption; split volumes unsupported |
| RAR4 / RAR5 | Single archive | Linked libarchive-supported encryption; multipart unsupported |
| TAR / TAR.GZ | Single archive | No archive-level encryption |

An unsupported encrypted or multipart variant must fail explicitly and retain the
source archive. The archive support screen describes these limits. Integrity and
path/link checks still apply to every supported format; format recognition alone
is not successful extraction. Runtime fixture evidence is recorded separately.

## Storage-provider limits

Direct shared-storage edits use a staging file and explicit commit with original
identity revalidation. SAF providers differ: Files describes weaker in-place
save behavior before enabling it and retains a private editor draft. Generic SAF
folder deletion is withheld where a provider cannot guarantee exact reviewed
children, because `deleteDocument` may recursively delete unreviewed new content.
This limitation does not prevent supported file browsing, editing, copy, or trash.
