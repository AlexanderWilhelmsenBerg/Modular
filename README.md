# Modular

**Modular** is a deliberately minimal Android launcher framework in which almost all visible behavior is provided by independently installable modules.

The launcher Core owns lifecycle, module discovery, capability routing, state, security, context activation, Android HOME integration, and MCP brokering. App drawers, home screens, sorting, automation, terminal access, scripts, integrations, and other behavior belong in modules.

## Product idea

A clean installation should be intentionally bare:

- Android HOME integration
- a settings shell
- module management
- context/profile selection
- the minimum services required to discover and run modules

Everything else is optional.

A single module implementation may have multiple configured **instances**. For example, one App Drawer module can have a **Work** instance and a **Personal** instance with different filters, sort providers, layouts, and rules.

A **Context** composes module instances into the active launcher configuration. Contexts are manually switchable and can later be changed by automation modules such as time-based rules.

## Architecture principles

1. If functionality can reasonably be removed, it does not belong in Core.
2. Modules advertise capabilities rather than being hard-coded into a closed list of module types.
3. Module code and module instances are separate concepts.
4. External modules run as separate Android applications/processes rather than injecting arbitrary code into Core.
5. Core is the policy enforcement point for module capabilities and MCP.
6. Privileged capabilities such as terminal, scripts, module installation, and remote MCP actions require explicit policy and must never become implicit trust.
7. Android platform APIs remain behind Core-owned abstractions where they represent shared launcher state.
8. Stable contracts are versioned from the beginning.

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

See [ADR-0001](docs/decisions/ADR-0001-language-and-toolchain.md) for the research and alternatives.

## Specifications

- [Product specification](docs/product/product-spec.md)
- [System architecture](docs/architecture/system-architecture.md)
- [Module model and contract](docs/architecture/module-model.md)
- [MCP architecture](docs/architecture/mcp.md)
- [Security model](docs/architecture/security.md)
- [Language and toolchain ADR](docs/decisions/ADR-0001-language-and-toolchain.md)
- [Module packaging and IPC ADR](docs/decisions/ADR-0002-module-packaging-and-ipc.md)
- [Initial roadmap](docs/roadmap.md)

## Status

Pre-implementation architecture phase. The first milestone is to prove the module boundary with a tiny Core plus reference Home, Drawer, and alphabetical Sort modules before adding automation, terminal, scripts, or AI-driven behavior.
