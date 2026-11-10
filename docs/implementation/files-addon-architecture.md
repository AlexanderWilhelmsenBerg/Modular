# Modular Files implementation architecture

Baseline: Core `f19a5c5cf45eaab868e3148828776390dabf02f7`.
Scope: [accepted Files v1 specification](../product/file-manager-cleaner-v1.md).

## Ownership and module boundaries

- `addon-files`: separately installed APK; owns user-granted storage access, opaque document identities, scans, operation plans, trash, archive extraction, and private browsing/history state. Core does not request its storage permissions.
- `module-api`: bounded versioned `modular.workspace.page/v1` AIDL/JSON contract. Native page data and explicit action tokens cross Binder; add-on executable UI code does not load into Core.
- `module-sdk`: authenticated Core session endpoint, continuing the existing module trust and capability framework.
- `module-files-ui`: trusted native Compose file surface shared by Core and the standalone Files activity. Scoped file descriptors support integrated document viewing and editing.
- Core: persists one module component as a Home page, handles placement/discovery/disconnection, controls workspace gestures, and keeps Home/Apps/Settings reachable if a module is unavailable.

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
