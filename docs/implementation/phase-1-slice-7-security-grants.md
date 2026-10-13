# Phase 1 — Slice 7: Base Security and Capability Grants

Status: **Implemented, pending CI**

## Goal

Replace the temporary same-signer/all-capabilities bootstrap with a real,
persistent and revocable Core capability grant model.

Slice 7 establishes the authority boundary needed before richer reference
modules, event subscriptions, automation, scripts, MCP, or elevated providers
can safely consume Core services.

## Separate concepts

Core treats these as different things:

1. **module identity** — installed package plus current signing certificates;
2. **provided capabilities** — behavior the module offers to Core;
3. **requested Core capabilities** — Core services the module asks to consume;
4. **persistent grants** — authority Core has actually granted.

No item implies another.

## Protocol v3

Module protocol v3 adds transaction 8:

```text
getRequestedCoreCapabilities()
```

Existing transactions remain unchanged.

- protocol v1 remains metadata-only;
- protocol v2 retains Core-host sessions;
- protocol v3 adds requested-Core-capability declaration.

Older v1/v2 modules remain discoverable/supported but declare no requested Core
capabilities, so they receive no Core-host authority through the new grant
model.

## Signer-bound identity

`AndroidModuleIdentityResolver` resolves:

- package name;
- SHA-256 digest of each current APK content signer.

Digests are distinct and sorted to give deterministic identity.

Persistent grants bind to the complete identity, not just package name.

If the installed signer changes:

- the old grant record remains historical state;
- it no longer authorizes the package;
- existing authorization contexts fail closed;
- a new identity record is required.

Phase 1 deliberately does not auto-migrate grants across signing-key rotation.

## Persistent grant store

`DataStoreModuleGrantStateStore` stores a schema-versioned JSON envelope in
Preferences DataStore.

A grant record contains:

- exact `ModuleIdentity`;
- explicitly granted Core capability IDs.

An empty record is meaningful.

It means the identity has already been initialized with zero grants. This makes
revocation sticky and prevents reconnect from re-running bootstrap authority.

Storage rules:

- unknown JSON fields are tolerated within the known schema;
- future schema versions fail closed;
- malformed persisted capability IDs fail closed;
- I/O failures retain last-known in-memory state and surface a diagnostic error;
- mutations re-decode persistent state transactionally before writing.

## Official bootstrap

The Phase-1 official bootstrap applies only when:

- the exact identity has no existing grant record;
- the module's current signer set equals Core's signer set.

The initial record receives only:

```text
requested capabilities
∩ Core-supported capabilities
∩ explicit official bootstrap capabilities
```

Once that identity record exists:

- reconnect does not bootstrap again;
- revoked authority stays revoked;
- a module update requesting a new Core capability does not receive it
  automatically.

This removes the old behavior where same-signer modules effectively received
every Core capability.

## Third-party modules

An independently signed module is initialized with an explicit empty grant
record.

It may connect and provide capabilities to Core, but consumes no protected Core
capability until a later grant operation authorizes one.

The programmatic Phase-1 grant API enforces:

- capability must be supported by Core;
- capability must have been requested by the module;
- grant is written for the module's current exact identity.

Rich user-facing grant management is Phase 2.

## Live authorization

`CoreHostBinder` no longer receives a fixed allowed-capability snapshot.

For every protected operation it checks:

1. session is still active;
2. Binder caller UID still owns the expected package;
3. current installed signing identity still matches the session authorization;
4. capability is still requested;
5. capability is still supported;
6. persistent grant still exists.

This check occurs for:

- `IModularHost.getCapabilities()`;
- `IModularHost.getCapabilityBinder(...)`;
- every call on an already-issued Core capability Binder.

Therefore revocation is immediate. A module cannot preserve authority by
retaining an old Binder reference.

## Current Core capabilities

The existing Phase-1 Core host capabilities remain:

- `modular.apps.catalog/v1`;
- `modular.apps.launch/v1`.

The grant framework is generic over future versioned Core capabilities.

## Grant events

Slice 7 adds the internal event:

```text
core.grants.changed/v1
```

It reports added, removed, and changed signer-bound grant identity keys.

The Event Bus remains Core-internal. This event does not grant external modules
subscription authority.

## Recovery diagnostics

Core's recovery/settings screen now shows:

- number of persisted signer-bound identities;
- requested Core capability count;
- effective grant count;
- unsupported request count;
- grant-store error state.

Each discovered module also shows:

- requested Core capabilities;
- official vs external signer classification;
- abbreviated signer digest;
- currently effective grants;
- unsupported requested capabilities;
- signer-resolution errors.

This is read-only Phase-1 diagnostics, not the Phase-2 grant editor.

## Tests

JVM tests cover:

- protocol v3 compatibility;
- official bootstrap limited to requested capabilities;
- sticky revocation;
- third-party default deny;
- explicit grant of a requested supported capability;
- rejection of unrequested authority;
- signer-change invalidation;
- unsupported requested capability reporting;
- grant codec round trip;
- initialized-empty records;
- unknown-field tolerance;
- future-schema rejection;
- grant-change event diffs.

Android instrumentation covers:

- obtaining a real Core capability Binder;
- revoking the capability while the session/Binder object remains alive;
- host capability enumeration updating immediately;
- new Binder acquisition being denied;
- the previously issued capability Binder throwing `SecurityException`.

The existing separate-APK protocol/emulator smoke remains required and now
runs against module protocol v3.

## Deliberately deferred

- interactive grant editor/approval UX;
- risk-category presentation;
- signer-lineage migration/approval UX;
- event subscription grants;
- network/MCP grants;
- file/location/Bluetooth grants;
- script/terminal/elevated grants;
- remote-policy overlays and confirmations;
- audit history beyond current state;
- per-instance grants where future capability semantics require them.

## Acceptance

Slice 7 is accepted when:

- protocol v3 preserves v1/v2 compatibility;
- module requests are distinct from module-provided capabilities;
- grants persist against exact package + signer identity;
- third-party modules default deny;
- official bootstrap cannot silently expand after first initialization;
- signer replacement invalidates existing authorization;
- live revocation disables already-issued Core capability Binders;
- grant changes publish an internal typed event;
- recovery diagnostics expose security state without becoming a grant editor;
- unit tests, lint, APK assembly, and emulator security/Binder gates pass.
