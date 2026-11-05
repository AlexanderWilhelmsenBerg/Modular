# Module Model and Contract

Status: **Contract direction aligned with complete Core launcher — 2026-11-04**

## 1. Terminology

### Module package

An optional independently installable Android APK that implements the Modular service contract. The complete Core launcher is usable without any module packages. Built-in Home and alphabetical ordering are Core components, not required external modules.

### Module provider

The service endpoint within the APK that advertises one or more capabilities.

### Module instance

A Core-stored configuration that selects a module provider plus instance-specific configuration.

### Capability

A named, versioned behavior contract.

### Context

An optional named composition of Core settings and module instances. A default Core configuration exists without module instances.

## 2. Discovery

A compatible module declares:

- a Modular module service;
- module ID;
- module version;
- supported Core protocol range;
- provided capability IDs/versions;
- required Core capabilities;
- configuration schema metadata;
- human-readable name/description;
- optional module-management Activity.

Core discovers candidate services, validates them, then performs a Binder handshake before treating the module as available.

Manifest metadata is discovery metadata, not trusted authority. Core verifies runtime claims through the handshake.

## 3. Identity rules

Module IDs are stable and globally namespaced, for example:

`dev.modular.drawer.basic`

Instance IDs are Core-generated stable identifiers.

Capability IDs use stable namespace-like names, for example:

- `modular.surface.home/v1`
- `modular.surface.drawer/v1`
- `modular.apps.filter/v1`
- `modular.apps.sort/v1`
- `modular.context.trigger/v1`
- `modular.terminal.execute/v1`
- `modular.scripts.execute/v1`

## 4. IPC boundary

The cross-APK transport is Binder using AIDL.

AIDL should stay deliberately small. It provides:

- protocol handshake;
- manifest/capability query;
- instance/session opening;
- versioned request invocation;
- event/subscription callbacks where required;
- graceful close/cancellation.

Capability-specific models should be versioned and bounded. Avoid one giant ever-growing interface.

All potentially slow module work is asynchronous from the UI perspective. Binder callbacks do not authorize blocking the main thread.

## 5. Suggested conceptual handshake

```text
Core → Module: hello(coreProtocolVersion, coreCapabilities)
Module → Core: moduleManifest(protocolRange, capabilities, schemas)
Core → Module: openSession(instanceId, grants, context)
Module → Core: session ready / incompatibility
```

This is conceptual, not final AIDL syntax.

## 6. Capability composition

Core can extend its built-in behavior with optional capability pipelines. The default path uses Core's app repository, alphabetical ordering, and Home.

Example:

```text
apps source
   → optional filter instance
   → optional sort instance (built-in alphabetical default)
   → Core Home (optional external surface replacement)
```

A module may implement several related capabilities, but Core does not require them to be bundled.

## 7. Configuration

Each module capability/instance may expose a versioned configuration schema.

Core owns storage of the configuration envelope so context composition remains durable even when a module process is dead.

Sensitive module-owned secrets should not be copied into generic Core configuration. A module may retain its own private secret storage and expose only a reference/state through the contract.

## 8. UI-capable modules

Core Home is trusted built-in UI. Optional external presentation modules return a declarative, versioned surface model rather than arbitrary executable UI.

Surface Protocol v1 is established by `modular.surface.home/v1`.

The v1 document is intentionally small and declarative:

- `Column`;
- `Row`;
- `Text`;
- `Button`;
- `Spacer`.

Core owns validation and Compose rendering. Modules send no Compose objects,
Views, lambdas, DEX, or native drawing code across the IPC boundary.

Each surface document carries a schema version and revision. Core validates hard
limits for node count, depth, individual text length, total text volume, unique
node IDs, supported node/style values, and action IDs before rendering.

Button actions are routed back to the provider through the surface capability.
After an action succeeds, Core requests a fresh document. This keeps module
state and behavior behind the module boundary while rendering remains Core-owned.

Unsupported schemas or malformed trees fail closed to Core's recovery/settings
surface rather than executing partial or unvalidated module UI.

Future versions may add richer layout, accessibility metadata, theme tokens,
resources, incremental updates, and additional action/result semantics. Those
extensions must not silently change v1 behavior.

A module's own configuration Activity may use ordinary Android UI inside the
module APK.

## 9. Module instances: Work and Personal example

```text
Installed module
  Basic App Drawer
      │
      ├── Instance: Personal
      │     filter = personal
      │     sort   = alphabetical
      │
      └── Instance: Work
            filter = work
            sort   = priority
```

A time/context trigger activates the Work context. Core switches the active composition; it does not clone APKs.

## 10. Failure isolation

Core must handle:

- provider missing;
- provider disabled;
- provider crashes;
- binder dies;
- request timeout;
- invalid payload;
- unsupported schema/capability version;
- revoked grant.

A failed optional module should degrade the affected capability rather than crash the launcher.

Core shall restore its production built-in Home and alphabetical ordering if an optional surface or helper fails. No external Home provider is required. Settings remains reachable, invalid output is rejected, and failed context activation preserves the previous usable configuration. Provider removal or revocation must not leave a blank screen or diagnostics-only launcher.

## 11. Module development language

The official SDK and reference modules use Kotlin.

The IPC contract should remain callable from Java because AIDL generates JVM interfaces. Third-party internals may use native code behind their own boundary, but Core does not accept native binary plugins loaded into its process.
