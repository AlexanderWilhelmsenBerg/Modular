# Modular — Product Specification

Status: **Core requirements and agreed follow-up specifications (2026-11-05)**

This document captures the product intent established before implementation. Items marked **Open** are deliberately unresolved rather than guessed.

## 1. Product vision

Modular is a complete, minimal Android launcher. Installing the Core APK provides a usable Home screen with an alphabetical app list and app launching. Add-ons provide optional helper functions, integrations, and enhancements.

Core usability never depends on installing or configuring an add-on. See [ADR-0004](../decisions/ADR-0004-complete-core-launcher.md).

## 2. Core requirements

Core shall provide:

- Android HOME/default-launcher integration.
- a production Home screen with a vertical alphabetical text-only app list and tap-to-launch.
- built-in locale-aware alphabetical ordering.
- a secondary settings surface and reliable Home/Back navigation.
- module discovery, registration, enable/disable, compatibility validation, and removal from the active configuration.
- module-instance configuration.
- capability registration and routing.
- lifecycle management for module connections.
- a launcher event bus.
- persistent Core state.
- context creation, storage, selection, and activation.
- security policy and capability grants.
- a policy boundary for optional, later-phase MCP brokering.
- shared Android launcher abstractions where one authoritative system view is preferable, including application discovery/launching.

Core owns the complete baseline launcher, including Home, app discovery, alphabetical ordering, launching, settings, accessibility, and recovery. Terminal, script runtimes, automation, weather, integrations, and richer presentation are optional add-ons. A separate app drawer is not required: the default Home is the app list.

## 3. Modules established so far

Initial module concepts include:

- Optional alternative Home or drawer presentation.
- Alternative app sorting.
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

Contexts are optional advanced configuration and shall be manually switchable when configured. Core initializes a default configuration automatically; basic use requires no context or module instance setup. Activation validates the new configuration before committing and preserves the previous usable configuration on failure.

Automation modules may request a context change based on conditions such as time. Future trigger providers may include other device signals, subject to Android permissions and user policy.

Core exposes context activation. Trigger logic remains outside Core.

## 6. Application model

Core owns an application repository abstraction over Android launcher APIs. Its package visibility declaration must cover MAIN/LAUNCHER activities so ordinary installed launchable apps are discoverable, not only automatically visible system packages. The baseline must not apply a fixed app-count limit or optional filter. Android profile access restrictions still apply.

Modules consume a stable app model rather than directly defining independent package-discovery behavior.

The model must be capable of representing:

- launchable application/activity;
- user/profile identity;
- package/component identity;
- label/icon metadata;
- enabled/available state;
- changes over time.

Alphabetical ordering is built into Core. Optional filtering, tagging, grouping, and alternative sorting extend the shared model. Built-in search is an agreed follow-up requirement (section 15).

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

The external surface schema requires a focused design spike before external UI add-ons are implemented. It does not gate Core Home, which is trusted application UI. Initial external nodes should cover bounded lists, app rows, actions, loading/error states, accessibility, and incremental updates.

## 9. MCP product requirements

MCP is optional and deferred until the complete local launcher works. Core provides its policy boundary when enabled; no network listener or remote exposure is enabled merely by installing Core.

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

Python is the preferred first optional scripting runtime, consistent with ADR-0003 and the automation specification. Additional runtimes may be added through the same contract.

## 11. Baseline Home and navigation

- Installing only the Core APK shall provide Home without a network connection, account, extra APK, optional permission, or setup wizard.
- Cold launch and explicit app-icon or Android HOME intents shall show Home. A non-blocking Make default action may request the Android HOME role.
- Home is a vertically scrolling list of launchable activities, sorted by displayed label using the device locale. Equal labels have deterministic component/profile tie-breakers.
- Each default row displays only the app name and launches the exact component in its profile. Icons, profile subtitles, badges, and grouping are opt-in add-on presentation; Core must not show them by default. Profile identity remains internal for correct launching and availability checks. Duplicate labels must not collapse distinct activities.
- Disabled or unavailable activities must not appear actionable. Paused profiles and launch failures receive clear feedback without crashing Home or bypassing Android restrictions.
- Package and profile changes refresh the list. Loading, empty catalog, and discovery failure are distinct states; failures offer retry.
- Settings is explicitly reachable from Home. Back from Settings returns to Home. Back on baseline Home leaves Home visible. A HOME intent while Settings is open returns to Home, including reuse of the existing activity.
- Ordinary return from a launched app preserves Home scroll position where possible. Rotation/process recreation restores appropriate UI state; a newly delivered HOME or app-icon intent takes precedence and shows Home.
- TalkBack semantics, large text, adequate touch targets, contrast, and system insets are baseline requirements.
- Optional add-on failure shall retain or restore built-in Home and alphabetical ordering. Settings and add-on disable controls remain reachable.
- No separate drawer is required. Built-in search and theme selection are agreed follow-up Core requirements (section 15); favorites, an alphabet index, widgets, and alternative layouts remain optional enhancements.

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

The first slice delivers a complete, reliable Core launcher. External add-on proof follows baseline acceptance.

## 13. Acceptance

1. Install only the Core APK with no network and no add-ons. Open it and launch an app from the alphabetical Home list.
2. Set Modular as default. HOME from another app and from Settings shows Home.
3. Verify locale ordering, duplicate labels, exact activity/profile launch, unavailable profiles, and launch-error feedback.
4. Install, remove, and rename apps; verify the displayed catalog updates.
5. Verify rotation, process recreation, repeated HOME intents, and accessible navigation with TalkBack and large text.
6. Disable, remove, crash, or revoke authority from an optional add-on. Built-in Home, app launching, and Settings remain usable.
7. Attempt an invalid context activation; verify the previous usable configuration survives.
8. Separately prove a helper add-on can negotiate the versioned contract and be enabled, replaced, and removed without changing Core source.

These are required outcomes, not claims that the current implementation already passes.



## 14. Module management and recovery

These requirements complete the management surface around the existing module runtime:

- Settings lists discovered services with enabled state, connection status, compatibility information, and the most recent failure reason. Enable/disable persists across Core restarts. Disabling releases the session and its Core authority while retaining saved configuration.
- Discovery does not imply a permanent service connection. Settings may connect enabled modules for inspection; outside Settings only providers selected by the active context need a connection. Core Home needs no module. Previously unknown providers are inspected in Settings before selection.
- Retry explicitly tears down a failed attempt and starts a fresh connection. Deadlines cover binding and handshake. Late callbacks/results cannot revive a disabled, superseded, or removed module. A blocked Binder call must not cause an unbounded sequence of retries or freeze Home.
- A bounded, persistent local event history records timestamp, affected service/package, lifecycle stage, outcome, and actionable error details. Show newest entries first, support an errors filter, copying, and explicit clearing. Retain the latest 200 entries; exclude app catalogs, configuration payloads, and credentials. This diagnostic history is distinct from the transient launcher event bus.
- Instance management supports creation, renaming, configuration editing, and deletion. Provider identity is immutable for an existing instance. Invalid JSON or an instance referenced by a context must produce an error without losing the saved configuration.
- Context management supports creation, editing bindings, deletion, activation, and deactivation. Active bindings require deactivation before editing. Failed activation preserves the previous selection. Missing/disabled providers produce a visible degraded state without breaking Home.
- Permission controls display requested, supported Core capabilities and allow explicit grants/revocations using the installed signing identity. Authority is checked live; permissions apply to the package identity, not independently to each service in the same APK.
- “Uninstall” invokes Android's package removal confirmation and explains that every module in the APK is removed. Core cannot uninstall itself through this screen. Cancellation or platform refusal leaves the installed package and saved state intact. Refresh after returning from Android; report actual installed state rather than assuming success.
- Disabling a module, deleting an instance/context, and uninstalling its APK are distinct operations. Uninstall does not silently erase saved instances or contexts; unresolved references remain visible and can be repaired or explicitly deleted.

Acceptance: disable/re-enable and restart Core; retry a failed/blocked handshake; simulate stale callbacks, service death, and an incompatible module; restart and inspect retained events; clear history; edit invalid/referenced configuration; reject an invalid context switch; revoke permission during a session; cancel uninstall and then confirm uninstall. Home and Settings must remain reachable throughout.

## 15. Agreed next phase: Home controls and themes

These requirements are approved but are not part of the module-management implementation:

- Remove the “Apps” title from Home.
- Add a search control beside a settings gear icon, both with a 48 dp resting height and adequate touch targets. Search expands horizontally when focused, keeps the entered text/caret visible, and keeps Settings accessible. Keyboard Back dismisses editing before Home navigation. Clearing search restores the full alphabetical list; show an explicit no-results state.
- Use a gear icon with the accessible label “Settings” in place of the text button.
- Provide persisted Light, Dark, and AMOLED themes in Settings and apply them consistently to Home, Settings, dialogs, and system bars. AMOLED uses a true-black background with readable controls and text.
- Define a bounded theme extension contract before adding themes from external add-ons. Losing an add-on theme falls back to a built-in theme without blocking Home.
- Verify compact widths, font scaling, TalkBack, keyboard/insets, and contrast in all built-in themes. Do not add app icons or profile subtitles to the default list.
