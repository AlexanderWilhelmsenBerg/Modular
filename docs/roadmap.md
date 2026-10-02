# Initial Roadmap

This is sequencing, not a release-date commitment.

## Phase 0 — Contracts before features

- Core/module protocol spike.
- module discovery manifest.
- AIDL handshake.
- capability identifiers/versioning.
- process-death tests.
- declarative Surface Protocol spike.
- caller identity/security spike.

Exit: two separate test APKs can negotiate a versioned capability safely.

## Phase 1 — Minimum launcher kernel

- Android HOME role.
- Core settings/recovery surface.
- Module Registry.
- Capability Router.
- Instance Store.
- Context Manager.
- App Repository using launcher APIs.
- event bus.

Reference modules:

- Minimal Home.
- Basic App Drawer.
- Alphabetical Sort.

Exit: apps can be displayed/launched through modules rather than Core-owned drawer logic.

## Phase 2 — Instances and contexts

- module configuration schemas.
- multiple instances of one module.
- Personal and Work drawer instances.
- manual context switching.
- context-specific pipelines.

Exit: switching context changes module-instance composition without reinstall/rebuild.

## Phase 3 — Module management

- compatibility UX.
- add/remove/enable/disable flows.
- version/upgrade handling.
- capability grants.
- diagnostics.
- module crash/death recovery.

## Phase 4 — Automation

- `context.trigger` contract.
- first time-based context trigger module.
- conflict/priority policy.
- manual override semantics.

Core remains ignorant of time-rule business logic.

## Phase 5 — MCP

- official Kotlin MCP SDK integration.
- local policy model.
- context read tools.
- context activation tool.
- module-contributed MCP tool proof.
- authentication/audit.
- outbound MCP-client module proof.

## Phase 6 — Scripts and terminal

Only after the security/capability model is proven:

- Scripts capability/module.
- Terminal capability/module.
- sandboxed backend first.
- optional elevated providers only through separate ADRs and explicit policy.

## Later candidates

- search modules;
- app tagging/classification;
- usage/frequency sort;
- icon/theme modules;
- widget integration;
- richer trigger providers;
- module catalog/distribution;
- optional desktop module-development/test tooling.
