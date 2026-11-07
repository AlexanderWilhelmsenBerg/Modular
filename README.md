# Modular

**Modular** is a complete, minimal Android launcher with optional add-ons for complex integrations and alternative behavior.

## Current delivered behavior

Installing the Core APK provides:

- a Home screen with a vertical alphabetical app list;
- app icons and names, optional text-only rows, profile-aware discovery, and tap-to-launch;
- letter dividers, animated side alphabet navigation, and retained pull-down reachability;
- Android HOME integration;
- persistent full-width app search and a settings gear at the bottom;
- persisted Light, Dark, and true-black AMOLED themes;
- optional system wallpaper with adjustable dimming;
- a built-in, default-off Most Used section with pins and optional usage suggestions;
- secondary Settings with add-on controls, retained failure history, and Android-confirmed uninstall;
- accessibility and automatic app-list updates.

Home opens immediately. No extra APK, network, account, or context setup is required. Pins work without Usage Access; usage suggestions require an explicit Android grant to Core. Add-ons extend the finished Core with alternative sorting/presentation, automation, scripts, and integrations. Built-in Home remains available when an add-on fails.

A single module implementation may have multiple configured **instances**. For example, one App Drawer module can have a **Work** instance and a **Personal** instance with different filters, sort providers, layouts, and rules.

A **Context** composes module instances into the active launcher configuration.

## Architecture principles

1. Core delivers everyday launcher functions; complex integrations and alternative behavior belong in add-ons.
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
- [Home presentation specification](docs/product/home-presentation-v2.md)
- [Home presentation implementation plan](docs/implementation/home-presentation-v2-plan.md)
- [Core Most Used ownership and feature order ADR](docs/decisions/ADR-0006-core-most-used-and-feature-order.md)
- [Indexed Home and generic featured add-on contract ADR](docs/decisions/ADR-0005-indexed-home-and-featured-addons.md)
- [System architecture](docs/architecture/system-architecture.md)
- [Module model and contract](docs/architecture/module-model.md)
- [Add-on SDK and developer guide](docs/addon-development.md)
- [Protocol v4 descriptors and instance configuration](docs/architecture/addon-protocol-v4.md)
- [Core framework acceptance](docs/implementation/core-framework.md)
- [Automation and scripting](docs/architecture/automation-and-scripting.md)
- [MCP architecture](docs/architecture/mcp.md)
- [Security model](docs/architecture/security.md)
- [Language and toolchain ADR](docs/decisions/ADR-0001-language-and-toolchain.md)
- [Module packaging and IPC ADR](docs/decisions/ADR-0002-module-packaging-and-ipc.md)
- [Automation/scripting boundary ADR](docs/decisions/ADR-0003-scripting-boundary.md)
- [Complete Core launcher ADR](docs/decisions/ADR-0004-complete-core-launcher.md)
- [Delivery roadmap](docs/roadmap.md)
- [Android signing and parallel debug/release installation](docs/android-signing.md)
- [Module management implementation and remaining acceptance](docs/implementation/module-management.md)

## Delivery order

First complete Core Home, alphabetical ordering, launching, settings navigation, accessibility, and lifecycle/failure handling. Then validate optional helper add-ons and advanced contexts. External Home/Drawer modules are optional presentation replacements.

Automation, Python, MCP, and terminal capabilities follow later. See the [roadmap](docs/roadmap.md). The validated Core baseline includes search/gear controls, built-in themes, Settings module management, and selected optional sort-helper output with alphabetical fallback. External presentation and external theme loading remain future work. See [Core completion](docs/implementation/core-completion.md) for baseline test evidence and physical-device acceptance. The [Core framework](docs/implementation/core-framework.md) adds validated discovery descriptors, per-instance configuration, and a separately consumable SDK with an independent example.

## Home presentation v2

The [2026-11-07 specification](docs/product/home-presentation-v2.md) revises the
previous text-only presentation direction. Source now includes Core icons, letter
dividers, side alphabet navigation, optional system wallpaper/dimming, bottom
search/settings, and pull-down reachability. Built-in Most Used contributes a starred
section with saved pins before usage-ranked suggestions, while the full
alphabetical catalog stays available. Automated results are tracked against
[PR #19](https://github.com/AlexanderWilhelmsenBerg/Modular/pull/19); physical-device
acceptance remains pending. See the
[v2 acceptance matrix](docs/implementation/core-completion.md#home-presentation-v2-implementation-and-acceptance).


## Built-in Most Used

[Most Used](docs/addons/most-used.md) optionally adds a starred Home section in Core.
Saved pins come first in manual order and work without Usage Access; suggestions
follow by recorded foreground time when access is granted to Core. The built-in
toggle is off by default. Core keeps the full alphabetical catalog when the
feature is disabled. The Signed APKs workflow distributes Core debug and release
by default; reference add-on APKs are an optional SDK-testing output.

Basic categories will be specified after the Core Most Used implementation.

Module cards have a **Settings** button with normal controls and a **Use on Home**
action for sort helpers. Instances, contexts, and JSON editing are under Advanced
configuration and are not required for normal add-on setup.
