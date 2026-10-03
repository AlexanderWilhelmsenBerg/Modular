# Phase 1 — Core Launcher Foundation

Status: **In implementation**

This slice begins Phase 1 with a bootable Android launcher Core and the smallest useful external-module handshake.

## Included

- Android application scaffold.
- Android HOME/default-launcher declaration.
- HOME role request flow.
- minimal Compose recovery/settings surface.
- separate `module-api` Android library.
- v1 AIDL discovery/handshake contract.
- package-visibility declaration for module-service discovery.
- module registry backed by `StateFlow`.
- module process binding and basic death/failure handling.
- compatibility check for module protocol v1.
- CI build/lint/unit-test workflow.

## Deliberately not included yet

- a production Home module;
- app drawer;
- app repository / `LauncherApps`;
- capability invocation protocols beyond the handshake;
- capability grants;
- module signing/caller trust policy;
- persistent instance store;
- contexts;
- surface protocol;
- MCP;
- automation/scripts.

These remain Phase 1 follow-on slices or later roadmap work.

## Toolchain

Initial pinned toolchain:

- Android Gradle Plugin: 9.4.1
- Gradle: 9.6.0 in CI
- JDK: 17
- built-in Kotlin from AGP
- Compose compiler plugin: 2.2.10
- Compose BOM: 2026.09.00
- compileSdk: 37
- targetSdk: 36
- minSdk: 29

### Why compileSdk 37 but targetSdk 36?

Stable Compose 1.12.x requires compileSdk 37. Android 17 is still in beta at the start of this implementation, so the initial app compiles against API 37 while continuing to target the stable Android 16 behavior set.

The target can move to 37 after Android 17 reaches stable and its behavior changes are reviewed.

## Package identity

Initial application ID:

`io.github.alexanderwilhelmsenberg.modular`

The public module API namespace is:

`io.github.alexanderwilhelmsenberg.modular.moduleapi`

## v1 module discovery

Candidate module APKs expose an exported bound service for action:

`io.github.alexanderwilhelmsenberg.modular.action.MODULE`

Core discovers matching services, binds explicitly by component, and reads:

- protocol version;
- stable module ID;
- module version;
- provided capability IDs.

This is only the bootstrap handshake.

Security/caller-verification and capability-specific AIDL contracts must be designed before treating untrusted third-party modules as authorized providers.
