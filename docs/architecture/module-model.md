# Module Model and Contract

Status: **Initial contract direction**

## 1. Terminology

### Module package

An independently installable Android APK that implements the Modular service contract.

### Module provider

The service endpoint within the APK that advertises one or more capabilities.

### Module instance

A Core-stored configuration that selects a module provider plus instance-specific configuration.

### Capability

A named, versioned behavior contract.

### Context

A named composition of module instances.

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

Core builds pipelines by capability.

Example:

```text
apps source
   → filter instance
   → sort instance
   → drawer surface instance
```

A module may implement several related capabilities, but Core does not require them to be bundled.

## 7. Configuration

Each module capability/instance may expose a versioned configuration schema.

Core owns storage of the configuration envelope so context composition remains durable even when a module process is dead.

Sensitive module-owned secrets should not be copied into generic Core configuration. A module may retain its own private secret storage and expose only a reference/state through the contract.

## 8. UI-capable modules

For embedded launcher surfaces, modules return a declarative, versioned surface model rather than arbitrary executable UI.

Desired properties:

- deterministic rendering by Core;
- bounded payload size;
- stable accessibility semantics;
- action IDs routed back to the module;
- incremental updates;
- theme tokens rather than unrestricted drawing code;
- graceful handling of unsupported nodes;
- schema negotiation.

A module's own configuration Activity may use ordinary Android UI inside the module APK.

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

Recovery behavior for a missing required home surface is **Open**. A safe minimal fallback/recovery screen in Core is likely necessary even though it must not become a full Home implementation.

## 11. Module development language

The official SDK and reference modules use Kotlin.

The IPC contract should remain callable from Java because AIDL generates JVM interfaces. Third-party internals may use native code behind their own boundary, but Core does not accept native binary plugins loaded into its process.
