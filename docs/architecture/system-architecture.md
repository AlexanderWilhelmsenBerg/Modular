# System Architecture

Status: **Initial architecture**

## 1. Architectural style

Modular uses a microkernel/plugin architecture adapted to Android's application sandbox.

Core is the microkernel. Modules are independently installed Android applications that communicate with Core over a versioned IPC contract.

```text
Android
  │
  ▼
┌───────────────────────────────────────┐
│              Modular Core             │
│                                       │
│ HOME role / lifecycle                 │
│ Module Registry                       │
│ Capability Router                     │
│ Instance Store                        │
│ Context Manager                       │
│ App Repository                        │
│ Event Bus                             │
│ Security / Grants                     │
│ Surface Renderer                      │
│ MCP Broker                            │
└───────────────┬───────────────────────┘
                │ Binder/AIDL
       ┌────────┼─────────────┐
       ▼        ▼             ▼
    Home APK  Drawer APK   Sort APK ...
```

## 2. Core components

### Home integration

Core qualifies for and requests Android's HOME role and provides the Activity/lifecycle required of the default launcher.

### Module Registry

Discovers compatible module services, performs handshake/version checks, records module metadata, and tracks availability.

### Capability Router

Resolves a requested capability to an enabled provider/instance.

Examples:

- `surface.home`
- `surface.drawer`
- `apps.sort`
- `apps.filter`
- `context.trigger`
- `terminal.execute`
- `scripts.execute`
- `mcp.tools`

Capabilities are identifiers with versioned contracts, not Kotlin class loading across APK boundaries.

### Instance Store

Stores configured instances separately from installed modules.

An instance contains:

- stable instance ID;
- module/provider ID;
- configuration schema version;
- configuration data;
- enabled/disabled state;
- granted capabilities/policies where instance-scoped policy is appropriate.

### Context Manager

Stores named launcher compositions and atomically activates one.

Core owns the current-context state. Modules may request activation when granted the capability; they do not mutate Core storage directly.

### App Repository

Owns the authoritative launcher view of installed launchable applications and profiles.

This isolates Android-specific discovery and callbacks from drawer/sort/filter modules and prevents each plugin from independently reimplementing package visibility.

### Event Bus

Publishes typed, versioned launcher events such as:

- module added/removed/changed;
- app added/removed/changed;
- active context changed;
- configuration changed;
- permission/grant changed.

The event bus must support backpressure/lifecycle rules rather than assuming permanently bound module processes.

### Surface Renderer

Renders a versioned declarative surface model returned by UI-capable modules.

The module describes presentation/state/actions. Core performs the actual embedded launcher rendering.

Full details are deferred to the Surface Protocol design spike.

### MCP Broker

Maps explicitly exported, policy-approved launcher/module capabilities into MCP tools/resources and can host MCP client connections on behalf of modules/policies.

MCP is not the internal plugin transport.

## 3. Data flow example: app drawer

```text
LauncherApps
    │
    ▼
Core App Repository
    │
    ▼
Work Filter instance
    │
    ▼
Alphabetical Sort instance
    │
    ▼
Basic Drawer instance
    │
    ▼
Core Surface Renderer
```

A Personal context can use the same Drawer module with a different filter and sorter.

## 4. Process model

External modules run in their own Android application/process by default.

Reasons:

- preserves Android UID sandboxing;
- a faulty module is less likely to crash Core;
- dependency/version conflicts stay within the module APK;
- independent install/update is possible;
- security policy can be enforced at an IPC boundary.

Core must not dynamically load arbitrary DEX/native libraries from untrusted module packages into its own process.

## 5. Lifecycle

Module bindings are demand-driven.

Core should not permanently bind every installed module. Android service bindings affect process importance and memory behavior, so bindings should be scoped to active needs and support idle release.

The module protocol must tolerate:

- process death;
- rebinding;
- stale module versions;
- module removal/update;
- timeout;
- binder death;
- context changes while work is in flight.

## 6. State model

Core-owned state follows a single-source-of-truth/unidirectional data-flow design.

Recommended implementation direction:

- Room for structured relational state if/when required;
- DataStore for small Core preferences/configuration;
- StateFlow/Flow for reactive state;
- ViewModels/state holders for screen state;
- Compose for Core-rendered UI.

Exact persistence schema is intentionally deferred until the module contract stabilizes.

## 7. Compatibility/versioning

Version from day one:

- Core API version;
- module protocol version;
- individual capability contract version;
- module manifest schema version;
- surface schema version;
- instance configuration schema version.

Compatibility negotiation occurs during module handshake.

Breaking capability changes require a new capability major version rather than silently changing existing semantics.

## 8. Android platform constraints

Relevant platform facts:

- Android's HOME role is the supported mechanism for a default launcher.
- `LauncherApps` supplies launcher-oriented application/profile operations and change callbacks.
- separate applications are isolated by Android UIDs/sandboxing.
- cross-application service contracts can use Binder/AIDL.
- AIDL contracts must preserve backward compatibility after release.

These constraints drive the architecture rather than being hidden behind framework assumptions.

## 9. Open architecture questions

Before production implementation, resolve:

1. exact declarative Surface Protocol node set;
2. module discovery intent/metadata names;
3. binder authentication/caller-verification strategy for third-party-signed modules;
4. maximum binder payloads and paging/streaming strategy;
5. capability grant persistence and UX;
6. module update compatibility behavior;
7. package distribution/install UX;
8. whether any official modules should also be build-time modules for recovery/fallback purposes while still using the public contract.
