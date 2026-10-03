# Initial Roadmap

This is sequencing, not a release-date commitment.

## Phase 0 — Architecture and contracts

- product specification;
- language/toolchain decisions;
- Core/module protocol design;
- module discovery contract;
- AIDL handshake design;
- capability identifiers/versioning;
- process-death behavior;
- declarative Surface Protocol design spike;
- caller identity/security design spike;
- automation/scripting boundary.

Exit: implementation-ready contracts for the first Core slice.

## Phase 1 — Core launcher and module proof

Build the launcher kernel first.

### Core

- Android HOME role.
- Core settings/recovery surface.
- Module Registry.
- Capability Router.
- Instance Store.
- Context Manager.
- App Repository using launcher APIs.
- event bus.
- base security/grant infrastructure.

### Reference modules

- Minimal Home.
- Basic App Drawer.
- Alphabetical Sort.

### Acceptance

- separate APK modules negotiate the versioned contract;
- apps can be displayed/launched through modules rather than Core-owned drawer logic;
- Work and Personal can be separate instances of the same Drawer module;
- manual context switching changes active composition;
- module process death/removal is recoverable;
- Core does not need modification to replace a reference capability provider.

## Phase 2 — Module UX and richer composition

- module configuration schemas.
- richer instance configuration.
- add/remove/enable/disable flows.
- compatibility UX.
- version/upgrade handling.
- capability grants UX.
- diagnostics.
- module crash/death recovery UX.
- richer filtering/sorting/search capabilities.

## Phase 3 — Automation foundation

- Automation module/capability.
- trigger contract.
- action/workflow model.
- scheduling provider.
- event provider model.
- conflict/debounce/retry policy.
- manual override semantics.
- execution history.

First trigger examples:

- manual/quick action;
- time/schedule;
- Bluetooth event.

First Android-facing action capabilities should include enough to prove file, network, notification, and location composition.

## Phase 4 — Script runtimes

### Python first

- Script Runtime contract.
- Python/CPython Runtime module.
- Modular Python SDK/capability proxies.
- script manifest.
- source/config/state storage.
- validation and dry-run.
- timeouts/cancellation/logging.
- lightweight default dependency profile.
- optional Data profile after package/build-size validation.

### Acceptance automations

1. Quick action to organize PDF files in Downloads through a File module.
2. Daily webpage-change checker through Schedule + HTTP + State + Notification capabilities.
3. On selected Bluetooth disconnect, request and store one location fix through Bluetooth + Location capabilities.
4. A non-trivial local data transformation demonstrating optional NumPy/dataframe support where available.

### Later runtimes

The runtime contract must permit:

- Lua;
- JavaScript;
- WebAssembly/WASI;
- other runtimes without changing Core.

## Phase 5 — MCP authoring and control

- official Kotlin MCP SDK integration.
- policy model and audit trail.
- capability/schema discovery.
- context read/activation tools.
- module-contributed MCP tool proof.
- script generation workflow.
- script validation/install/update workflow.
- authentication.
- outbound MCP-client module proof.

Primary scripting goal:

> A user can describe an automation in natural language; MCP can inspect available capabilities, generate an inspectable script + manifest, validate required grants, and install it after user approval.

## Phase 6 — Terminal and elevated providers

Only after the security/capability model is proven:

- Terminal capability/module.
- sandboxed backend first.
- optional ADB/Shizuku/root providers only through separate ADRs.
- explicit policy for remotely invoked terminal actions.

Terminal is not the Python scripting architecture and must not become a shortcut around capability grants.

## Later candidates

- icon/theme modules;
- widget integration;
- richer trigger providers;
- module catalog/distribution;
- optional desktop module-development/test tooling;
- additional script-runtime profiles;
- isolated execution for untrusted third-party scripts.
