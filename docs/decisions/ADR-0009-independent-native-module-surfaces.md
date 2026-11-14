# ADR-0009: Independently rendered native module screens

Date: 2026-11-13. Status: selected for implementation; device evidence is tracked
with the implementing pull request.

## Problem

A separately installed add-on must be able to introduce and update its own UI
without a Core rebuild. The Files-specific data renderer compiled into Core did
not meet that requirement. Expanding a fixed Core widget catalogue would still
require Core updates when an add-on needs an unfamiliar control.

## Decision

Use the versioned `modular.workspace.surface/v1` capability to embed native UI
rendered in the add-on's own Android process. `SurfaceControlViewHost` produces a
platform `SurfacePackage`; Core attaches it to a `SurfaceView`. Core never loads
add-on DEX, Compose classes, native libraries, document renderers, or feature
controllers. A provider can use Views, Compose, custom drawing, or a WebView
without registering a new renderer in Core.

Core owns discovery, explicit enablement, signer-pinned placement, its visible
viewport, lifecycle delivery, Back/navigation, unavailable-module recovery, and
existing separately granted Core capabilities. The add-on owns its interface,
resources, settings Activity, permissions, network/storage work, persistence,
and operations. A private library such as `module-files-ui` belongs only to its
add-on; it is not a public framework dependency.

The existing root protocol remains v4. New functionality is an additive, major-
versioned capability, not a change to old Binder transactions. SDK 0.4.0 includes
an authenticated endpoint and view-tree lifecycle, saved-state, ViewModel, and
Back owners. Older data-only workspace endpoints remain in the public SDK for
older Core installations. New Core uses a generic update/fallback message for
such endpoints rather than shipping a Files renderer.

## Surface contract

A session exchanges a host token, bounded viewport/theme JSON, a SurfacePackage,
and generic callbacks. It supports host updates, Back dispatch, closure, and
navigation to Home, Apps, adjacent pages, or module settings. State flags declare
whether the add-on can go Back and needs exclusive gestures. These flags contain
no feature screen names. A provider opens its declared management Activity with
an opaque nonce when an Android permission/picker or Activity is needed; it
cannot supply arbitrary launch targets through Core.

Binder calls authenticate the live Core instance. Core callbacks check the
provider UID, session, route, visibility, and Activity lifecycle. Session
revocation, host death, timeouts, and detach release the hosted view. Viewport and
message bounds are validated on both sides. Android-specific APIs needing an
Activity should use the add-on's own Activity rather than assume that an
embedded View is an Activity.

Native embedding requires Android 11/API 30. Core retains its Android 10/API 29
minimum with an explicit standalone Activity fallback when one is declared.
No page launches an Activity automatically on entry. Native surfaces never paint
behind Core's controls or add another system-bar padding inside their viewport.

## Migration and compatibility evidence

Files now renders its existing UI in its own process and shares its local UI
pipeline with its standalone Activity. Its storage and deletion checks remain
in Files. Core's old Files snapshots, scroll state, and renderer dependency are
removed; Files persists its own location and scroll state.

The standalone page example consumes published API/SDK AARs with an independent
signer. Its v1 and v2 APKs use the same package but different UI and version codes.
The device proof installs v2 over v1 and asserts that Core's version, update time,
APK path, and SHA-256 are identical. It also checks native input, Back,
reattachment, adjacent-page navigation, and a newly introduced v2 control.
A separate boundary check rejects add-on UI dependencies/classes in Core.

Core needs one framework update for this migration. Subsequent add-on changes
using this surface contract need only the add-on APK. A future change to Core's
own behavior or a breaking host protocol still requires a Core update and a new
capability major version; it must not be disguised as an add-on release.

## Sources

- [SurfaceControlViewHost](https://developer.android.com/reference/android/view/SurfaceControlViewHost)
- [SurfaceView.setChildSurfacePackage](https://developer.android.com/reference/android/view/SurfaceView#setChildSurfacePackage(android.view.SurfaceControlViewHost.SurfacePackage))
- [API contract](../../module-api/src/main/java/io/github/alexanderwilhelmsenberg/modular/moduleapi/WorkspaceSurfaceContract.kt)
- [SDK endpoint](../../module-sdk/src/main/java/io/github/alexanderwilhelmsenberg/modular/modulesdk/WorkspaceSurfaceEndpoint.kt)
