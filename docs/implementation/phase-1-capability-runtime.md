# Phase 1 — Slice 3: Capability Runtime

Status: **Implemented, pending final CI**

## Goal

Turn module capability advertisements into a real versioned runtime boundary.

Before this slice, Core could discover module APKs and read capability strings, but it could not:

- establish a durable module session;
- expose Core-owned capabilities back to a module;
- obtain a capability-specific Binder from an external module;
- route exact versioned capability contracts.

Slice 3 adds that infrastructure without implementing Home, Drawer, Sort, or Context policy.

## Versioned capability identity

Capabilities use canonical IDs:

`modular.<domain>.<name>/v<major>`

Examples:

- `modular.apps.catalog/v1`
- `modular.apps.launch/v1`
- future `modular.apps.sort/v1`
- future `modular.surface.home/v1`
- future `modular.surface.drawer/v1`

Core rejects malformed capability advertisements during module handshake.

A major version is part of identity. Core never silently treats v2 as v1.

## Root Binder rule

Root Binder interfaces are brokers, not feature APIs.

`IModularModuleService` and `IModularHost` perform:

- version discovery;
- capability discovery;
- session establishment;
- capability-Binder acquisition.

They do **not** accumulate the methods of every future capability.

Each capability has a narrow versioned AIDL contract.

Slice 3 introduces:

- `IAppCatalogCapability` for `modular.apps.catalog/v1`;
- `IAppLaunchCapability` for `modular.apps.launch/v1`.

Future Sort, Home, Drawer, scripting, or other capabilities receive their own contracts.

## Module protocol v2

Protocol v1 remains metadata/discovery-only.

Protocol v2 adds:

1. `openSession(sessionId, host)`
2. `closeSession(sessionId)`
3. `getCapabilityBinder(sessionId, capabilityId)`

The original v1 AIDL transaction codes are explicitly pinned to 1-4. New v2 methods use 5-7.

This keeps the existing discovery ABI stable as the interface grows.

## Session model

Each connected v2 module receives a dedicated, revocable `IModularHost` Binder.

Core records:

- module component;
- module ID/version;
- protocol version;
- advertised capabilities;
- session ID;
- module process death recipient;
- Core-host session revocation handle.

If the module process dies, the binding is removed, or Core closes the session, all Core Binder endpoints issued for that session are revoked.

A module cannot retain an old app-catalog/app-launch Binder and continue using it after its session ends.

A connected protocol-v1 module remains discoverable but is not published as an invokable capability provider because it has no session.

## Bootstrap trust policy

Installing a module does not grant it Core authority.

Until the user-managed capability grant model is implemented, Core uses a deliberately narrow Phase-1 bootstrap policy:

- modules signed with the **same signer as Core** receive the currently defined Core capabilities;
- modules signed by anyone else receive an empty Core-capability set.

A third-party-signed module can still be discovered and can advertise its own capabilities, but it cannot read the Core app catalog or launch applications through Core merely because it is installed.

The explicit grant system will replace this bootstrap rule for community modules later.

## Core host Binder

The per-session host advertises only capabilities currently allowed for that session and returns only those capability Binders.

Each root and capability call verifies:

1. the session is still active;
2. the Binder calling UID belongs to the package for which the session was created;
3. the requested Core capability is allowed for that session.

Repeating the checks on returned capability Binders means leaking a Binder reference does not implicitly grant another package access.

## App catalog paging

A permitted module acquires `modular.apps.catalog/v1`, converts the returned Binder with `IAppCatalogCapability.Stub.asInterface(...)`, and reads the catalog in two steps:

1. `getInfo()`
2. `getPage(expectedGeneration, offset, limit)`

The info response includes:

- catalog generation;
- total app count;
- visible profiles;
- refresh/error state.

Pages are limited to at most 100 launchable activities.

Every page request includes the generation obtained from the info call. If the repository changes while the caller is paging, Core returns `STATUS_STALE_GENERATION`.

The caller then restarts against the new generation.

This prevents a module from accidentally combining pages from different device states and prevents unbounded Binder payloads.

## App launch

A permitted module acquires `modular.apps.launch/v1` and invokes `IAppLaunchCapability.launch(...)`.

The request uses the same stable key returned by the catalog:

- package name;
- exact activity name;
- persistent profile serial.

Core verifies that the key is still present in the current catalog before delegating to `LauncherApps.startMainActivity()`.

## External capability Binder acquisition

A v2 module advertises capability IDs during the handshake.

When Core needs one of those capabilities:

1. Capability Router resolves the exact capability/provider route.
2. Module Registry verifies the module is connected and advertised the capability.
3. Core calls `getCapabilityBinder(sessionId, capabilityId)`.
4. The caller converts the returned `IBinder` using the capability-specific AIDL interface.

The Core and module sides therefore use the same capability-Binder pattern.

## Capability Router

The router currently publishes:

- Core-owned capabilities;
- all invokable capabilities from connected v2 modules.

It deliberately does **not** choose between multiple external providers.

Provider selection belongs to Instance/Context policy in a later Core slice.

## Explicitly deferred

- user-managed capability grants;
- community-module trust approval;
- persistent instance selection;
- Context Manager;
- generic retry policy;
- Home/Drawer/Sort capability-specific AIDL;
- Surface Protocol;
- MCP;
- automation/scripts.

## Slice 3 acceptance

This slice is accepted when:

- v1 discovery remains compatible;
- v2 session exchange compiles;
- malformed capability IDs are rejected;
- Core apps catalog/launch use separate capability Binders;
- stale Core capability Binders fail after session revocation;
- unsigned/differently-signed modules receive no Core capability authority by default;
- catalog paging detects stale generations and enforces page bounds;
- external capability routes can resolve to raw Binder endpoints;
- module process death removes and revokes the active session;
- unit tests, Android lint, and debug APK assembly pass.
