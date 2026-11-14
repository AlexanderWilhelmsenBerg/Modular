# Independent native workspace surfaces

Source baseline: `2012e1330873edfe6fde62597b552e2ed67f41d0`.
Decision: [ADR-0009](../decisions/ADR-0009-independent-native-module-surfaces.md).

## Ownership

| Part | Owner | Boundary |
| --- | --- | --- |
| Home navigation, placement, lifecycle and recovery | Core | Generic platform surface; no add-on renderer |
| Interface, feature logic, data, permissions and saved position | Add-on APK | Remains in the provider's process |
| Versioned Binder and bounded metadata | `module-api` | `modular.workspace.surface/v1` |
| View-tree owners, sessions and gesture helper | `module-sdk` | Public SDK, independent of Files and Core UI |
| Files UI and document libraries | Files/private `module-files-ui` | Never a Core dependency |

## Implementation

The API defines session creation, SurfacePackage transport, host updates, Back,
closure, and callbacks for generic navigation/state. The SDK authenticates each
Binder call, bounds sessions, marshals view operations to Main, and tears down on
revocation or host death. Core checks callback identity and the current active
route, reserves its chrome, and closes stale surface sessions.

Files retains its legacy data endpoint for older Core versions and supplies its
new native surface through the same module service. Its Activity and surface
share asynchronous controller actions, document operations, scroll persistence,
and error handling. Input policy is computed in Files and sent as generic state.
An upward drag beyond the browser's scrolling boundary can request Apps.

Basic embedded Views start at Android 11. Per-provider `workspaceSurfaceMinSdk`
defaults to 30; Files and the native-window example require 33 for nested popup
and dialog support. Below that minimum, Core offers an explicit standalone
fallback declared by `workspaceActivity`; settings uses `managementActivity`.
Neither declaration grants storage access or invokes an Activity on page entry.

## Required checks

- Public API/SDK unit tests and lint, legacy Binder tests, Core launcher regression
  tests, and Files storage/deletion tests.
- Build standalone sort and native page add-ons using published SDK 0.4.0 AARs.
- Reject Files UI/controller dependencies and classes in Core source and APK.
- API 35 device tests for real cross-process input, module popups and dialogs,
  Back, detach/reattach, and navigation. API 30 verifies explicit standalone
  fallback and provider-only updates without changing Core.
- Install standalone v1 then v2 while Core's installed APK fingerprint remains
  unchanged. Save both native screenshots and the fingerprint comparison.
- Re-run the Files Light/Dark/AMOLED and font-size capture matrix against its
  provider-owned UI; renderer fixtures execute in the Files APK.
- Verify the Files-only signing path builds and publishes no Core APK.

Passing compilation is not evidence of surface embedding or keyboard behavior.
Commit-specific CI and screenshot results are recorded on the pull request;
physical OEM/device checks remain distinct from emulator evidence.
