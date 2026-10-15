# Phase 1 — Slice 8: Surface Protocol and Minimal Home

Status: **Implemented in PR #8; final review and CI acceptance pending**

## Goal

Prove that an independently installed module APK can provide the active launcher
Home presentation while Core retains rendering, validation, composition,
lifecycle, recovery, and IPC authority.

## Capability

Slice 8 introduces:

```text
modular.surface.home/v1
```

The capability Binder exposes:

- `render(instanceId)`;
- `performAction(instanceId, actionId)`.

The module returns declarative data. It never sends executable UI into Core.

## Surface Protocol v1

A document contains:

- schema version;
- monotonic provider revision;
- one root node.

Supported nodes are deliberately limited to:

- Column;
- Row;
- Text;
- Button;
- Spacer.

Core validates the complete tree before rendering it with Compose. The\nParcelable node decoder also enforces depth and node-count bounds *during*\nunmarshalling, before typed child-list allocation. String decoding still\noccurs inside Android Parcel/Binder and is not a substitute for transport-level\nresource limits.

Current hard limits include:

- maximum 128 nodes;
- maximum depth 12;
- maximum 512 characters per text node;
- maximum 8 KiB total text;
- bounded node/action identifiers;
- unique node IDs;
- known node and text-style values.

Malformed or unsupported surfaces are rejected and Core recovery/settings stays
available.

## Action model

A Button contains an opaque action ID.

Core sends that action ID back to the same surface provider and instance. The
module handles the action behind its process boundary. On success Core requests
a fresh document.

This proves interactivity without making the module's implementation language,
UI framework, or internal state part of Core.

## Minimal Home reference module

`reference-home-minimal` is a separate Android application and UID.

It provides `modular.surface.home/v1` and requests no Core capabilities.

The reference document shows:

- Modular heading;
- Minimal Home heading;
- a statement that the surface came from a separate APK;
- action state;
- a `Ping module` button.

The ping action increments module-owned per-instance state. A subsequent render
returns an updated revision and message.

The reference UI is intentionally plain. Its purpose is to prove the protocol,
not establish final launcher visual design.

## Composition

A Home surface remains instance/context driven.

A context binds `modular.surface.home/v1` to a configured module instance.
Core resolves that route through the existing Capability Router and
Context Manager.

No Minimal Home implementation is special-cased in Core.

## Testing

Unit tests cover:

- valid bounded documents;
- duplicate node IDs;
- excessive nesting;
- unsupported node types;
- deterministic Minimal Home document/action metadata.

The emulator canary installs the Minimal Home APK separately and proves:

1. the module has a distinct Android UID;
2. the capability is discovered through the normal module protocol;
3. a context binds a Home instance;
4. Core obtains and validates the surface across Binder;
5. Core routes an action back across Binder;
6. a fresh render reflects module-owned state.

The existing sort-module canary continues to run in the same emulator job.\nInstrumentation XML results are uploaded on successful and failed runs; on\nfailure the script records its stage, bounded logcat, ADB state and relevant\npackage/activity diagnostics. The prior multiline-emulator-runner shell\nparsing problem is avoided by invoking one standalone Bash script.

## Deliberately deferred

- rich Home layout/design;
- app icons and launch nodes;
- Drawer surface protocol;
- search;
- image/resource transport;
- accessibility extensions beyond Core-rendered defaults;
- theme-token protocol;
- incremental/diff surface updates;
- configuration UI;
- automatic creation of a default Minimal Home instance/context;
- third-party distribution and trust UX.

## Acceptance

Slice 8 is complete when the branch passes:

- module-api tests;
- Core unit tests;
- Minimal Home unit tests;
- lint;
- APK assembly;
- existing cross-APK sort smoke;
- cross-APK Home render/action smoke.
