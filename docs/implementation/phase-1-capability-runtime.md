# Phase 1 — Slice 3: Capability Runtime

Status: **Implemented, pending CI**

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

## Module protocol v2

Protocol v1 remains metadata/discovery-only.

Protocol v2 adds:

1. `openSession(sessionId, host)`
2. `closeSession(sessionId)`
3. `getCapabilityBinder(sessionId, capabilityId)`

The original v1 AIDL transaction codes are explicitly pinned to 1-4. New v2 methods use 5-7.

This keeps the existing discovery ABI stable as the interface grows.

## Session model

Each connected v2 module receives a dedicated `IModularHost` Binder.

Core records:

- module component;
- module ID/version;
- protocol version;
- advertised capabilities;
- session ID;
- module process death recipient.

If the module process dies, its session and routes are removed.

A connected protocol-v1 module remains discoverable but is not published as an invokable capability provider because it has no session.

## Core host Binder

The per-session Core host exposes:

- host protocol version;
- Core capability list;
- `apps.catalog/v1`;
- `apps.launch/v1`.

Each call verifies that the Binder calling UID belongs to the package for which the session was created.

This is the first identity check, not the final grant model. Signature/trust policy and per-capability grants remain future slices.

## App catalog paging

A module reads the catalog in two steps.

1. `getAppCatalogInfo()`
2. `getAppCatalogPage(expectedGeneration, offset, limit)`

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

`apps.launch/v1` accepts the same stable key used by the catalog:

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

This lets each capability have a narrow typed contract without growing one giant root module interface.

## Capability Router

The router currently publishes:

- Core-owned capabilities;
- all invokable capabilities from connected v2 modules.

It deliberately does **not** choose between multiple external providers.

Provider selection belongs to Instance/Context policy in a later Core slice.

## Explicitly deferred

- capability grants;
- signature/trust policy;
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
- Core exposes apps catalog/launch through a per-session host Binder;
- catalog paging detects stale generations and enforces page bounds;
- external capability routes can resolve to raw Binder endpoints;
- module process death removes the active session;
- unit tests, Android lint, and debug APK assembly pass.
