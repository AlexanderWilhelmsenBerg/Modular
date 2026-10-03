# Modular

**Modular** is a deliberately minimal Android launcher framework in which almost all visible behavior is provided by independently installable modules.

The launcher Core owns lifecycle, module discovery, capability routing, state, security, context activation, Android HOME integration, and MCP brokering. App drawers, home screens, sorting, automation, terminal access, script runtimes, integrations, and other behavior belong outside the Core.

## Product idea

A clean installation should be intentionally bare:

- Android HOME integration
- a settings/recovery shell
- module management
- context/profile selection
- the minimum services required to discover and run modules

Everything else is optional.

A single module implementation may have multiple configured **instances**. For example, one App Drawer module can have a **Work** instance and a **Personal** instance with different filters, sort providers, layouts, and rules.

A **Context** composes module instances into the active launcher configuration.

## Architecture principles

1. If functionality can reasonably be removed, it does not belong in Core.
2. Modules advertise capabilities rather than being hard-coded into a closed list of module types.
3. Module code and module instances are separate concepts.
4. External modules run as separate Android applications/processes rather than injecting arbitrary code into Core.
5. Core is the policy enforcement point for module capabilities and MCP.
6. Stable contracts are versioned from the beginning.
7. **Modules interact with Android; scripts orchestrate module capabilities.**
8. Scripts and script runtimes are not Android plugins and receive no implicit Android-framework access.
9. MCP may author scripts, but it cannot bypass capability grants or install hidden behavior.

## Language and platform decision

The normative implementation language is **Kotlin**.

- Core: Kotlin
- official module SDK and reference modules: Kotlin
- UI host: Jetpack Compose
- asynchronous/state model: Kotlin coroutines + Flow
- Gradle configuration: Kotlin DSL
- cross-process interface definition: AIDL
- MCP: official MCP Kotlin SDK
- optional native internals: C/C++ or Rust only when justified behind a Kotlin/JNI boundary

Python is the preferred **first scripting runtime**, not a Core or Android-integration language.

Future script-runtime providers may include Lua, JavaScript, WebAssembly/WASI, or others.

## Automation vision

Modular is intended to support Tasker-like automation with programmable scripts and MCP-assisted authoring.

Examples:

- create a launcher button which organizes PDFs in Downloads;
- check a webpage every day and notify only when it changes;
- when a chosen Bluetooth device disconnects, capture the current location;
- perform local NumPy/dataframe processing.

The script contains the logic. Android-facing File, Schedule, HTTP, Bluetooth, Location, Notification, and UI modules perform the platform operations.

## Specifications

- [Product specification](docs/product/product-spec.md)
- [System architecture](docs/architecture/system-architecture.md)
- [Module model and contract](docs/architecture/module-model.md)
- [Automation and scripting](docs/architecture/automation-and-scripting.md)
- [MCP architecture](docs/architecture/mcp.md)
- [Security model](docs/architecture/security.md)
- [Language and toolchain ADR](docs/decisions/ADR-0001-language-and-toolchain.md)
- [Module packaging and IPC ADR](docs/decisions/ADR-0002-module-packaging-and-ipc.md)
- [Automation/scripting boundary ADR](docs/decisions/ADR-0003-scripting-boundary.md)
- [Initial roadmap](docs/roadmap.md)

## Phase 1

Phase 1 is the Core launcher and proof of the external module boundary:

- Android HOME role;
- module discovery/IPC;
- capability routing;
- contexts and instances;
- app repository;
- minimal security/grants;
- Minimal Home, Basic Drawer, and Alphabetical Sort reference modules.

Automation, Python, MCP script authoring, and terminal capabilities are deliberately later phases. Phase 1 must only ensure its contracts do not prevent them.
