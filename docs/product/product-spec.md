# Modular — Product Specification

Status: **Initial specification**

This document captures the product intent established before implementation. Items marked **Open** are deliberately unresolved rather than guessed.

## 1. Product vision

Modular is an Android replacement launcher whose base application is a barebones framework. The launcher itself provides only the infrastructure necessary to host, compose, configure, secure, and route between modules.

Visible launcher features are modules.

The goal is not a monolithic launcher with an extension API added later. Modularity is the primary product model.

## 2. Core requirements

Core shall provide:

- Android HOME/default-launcher integration.
- a minimal settings surface.
- module discovery, registration, enable/disable, compatibility validation, and removal from the active configuration.
- module-instance configuration.
- capability registration and routing.
- lifecycle management for module connections.
- a launcher event bus.
- persistent Core state.
- context creation, storage, selection, and activation.
- security policy and capability grants.
- MCP brokering.
- shared Android launcher abstractions where one authoritative system view is preferable, including application discovery/launching.

Core shall not directly implement a production app drawer, rich home screen, app sorting strategy, terminal, script library, automation rule engine, weather, integrations, or similar end-user features.

## 3. Modules established so far

Initial module concepts include:

- Home surface.
- App drawer surface.
- App sorting.
- App filtering/classification.
- Context switching/automation.
- Terminal.
- Scripts for basic tasks.
- MCP tool/resource integrations.

This list is not a closed enum. New capabilities must be possible without adding a new hard-coded module category to Core where the generic capability model is sufficient.

## 4. Module versus module instance

A **Module** is installed executable functionality.

A **Module Instance** is a configured use of that module.

Example:

- Module: Basic App Drawer
- Instance: Personal
- Instance: Work

The two drawer instances may independently configure:

- application filters;
- app tags/categories;
- sorting provider;
- layout;
- labels;
- icon sizing;
- theme parameters;
- visibility rules.

This allows Work and Personal app drawers without installing duplicate implementations.

## 5. Contexts

A **Context** is a named composition of module instances that represents the active launcher configuration.

Example:

### Personal
- Personal Home instance
- Personal Drawer instance
- alphabetical or usage-based sorting
- personal scripts

### Work
- Work Home instance
- Work Drawer instance
- work-only application filter
- work-oriented sorting
- work scripts/integrations

Contexts shall be manually switchable.

Automation modules may request a context change based on conditions such as time. Future trigger providers may include other device signals, subject to Android permissions and user policy.

Core exposes context activation. Trigger logic remains outside Core.

## 6. Application model

Core owns an application repository abstraction over Android launcher APIs.

Modules consume a stable app model rather than directly defining independent package-discovery behavior.

The model must be capable of representing:

- launchable application/activity;
- user/profile identity;
- package/component identity;
- label/icon metadata;
- enabled/available state;
- changes over time.

Filtering, tagging, grouping, searching, and sorting are modular transformations over the shared model.

Android's `LauncherApps` API is the primary platform abstraction to evaluate for profile-aware launcher operations.

## 7. Module installation and discovery

Modules are intended to be independently installable Android packages.

Core shall discover compatible installed modules through an explicit module-service contract and manifest metadata.

"Install module" in the settings UX may eventually support handing an APK or store result to the Android package installation flow, but Core must not assume it can silently sideload packages.

Exact distribution channels are **Open**.

## 8. User interface model

Core owns the launcher rendering process.

External modules shall not inject arbitrary executable Compose code into Core.

For launcher-embedded surfaces, the initial architectural direction is a versioned declarative UI/surface protocol rendered by Core.

A module may own separate Android Activities for module-specific administration where appropriate, but the primary Home/Drawer composition must remain governed by the launcher contract.

The precise v0 surface schema is **Open** and requires a focused design spike before implementation.

## 9. MCP product requirements

Core provides an MCP broker/policy boundary.

MCP use cases include:

- exposing safe launcher capabilities to an MCP client;
- allowing modules to register tools/resources through Core;
- allowing selected modules to act as MCP clients to remote services;
- querying current context/module state;
- activating a context;
- launching an application;
- invoking explicitly granted module actions.

MCP must not bypass the module capability or security model.

Terminal execution, script execution, module installation/removal, and other high-impact actions must never become remotely callable merely because a module exposes them.

## 10. Terminal and Scripts

Terminal and Scripts are separate modules/capabilities, not Core features.

They execute within Android's security model unless an explicitly configured provider uses a stronger backend.

Potential future providers include:

- ordinary application sandbox;
- ADB/Shizuku-style elevated provider;
- rooted provider.

No elevated backend is assumed by this specification.

Script-language/runtime selection is intentionally deferred. Shell commands, JavaScript/Lua-style embedded runtimes, or other engines may be evaluated later as module implementations without changing Core.

## 11. Initial UX

A fresh installation may legitimately show no launcher content until a surface module is available.

Minimum UX:

- Settings
- Modules
- Contexts
- diagnostics/status

Reference modules should be supplied during development so the product remains usable while preserving the architectural rule that those features are not Core.

## 12. Non-goals for the first implementation slice

The first slice does not need:

- terminal;
- root;
- Shizuku;
- scripting runtime;
- AI assistant UX;
- remote module marketplace;
- complex automatic context switching;
- widgets;
- icon packs;
- weather;
- cloud account system.

The first slice exists to prove that the module boundary is real.

## 13. First architecture acceptance scenario

The architecture is considered proven when a build can:

1. become the Android HOME application;
2. discover separately packaged reference modules;
3. activate a Home module instance;
4. obtain the app model from Core;
5. pass it through an alphabetical Sort capability;
6. render it through a basic Drawer surface;
7. create two independently configured drawer instances;
8. switch between Personal and Work contexts without rebuilding Core;
9. disable/replace a module without modifying Core source.

MCP follows after the local capability boundary is demonstrated.
