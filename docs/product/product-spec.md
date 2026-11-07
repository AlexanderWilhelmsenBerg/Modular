# Modular — Product Specification

Status: **Delivered Core baseline plus specified Home v2 requirements (2026-11-07)**

Most Used ownership follows [ADR-0006](../decisions/ADR-0006-core-most-used-and-feature-order.md).
Earlier add-on-owned language in the Home v2 history is superseded. Basic
categories will be specified after Core Most Used is implemented.

This document captures the product intent established before implementation. Items marked **Open** are deliberately unresolved rather than guessed.

## 1. Product vision

Modular is a complete, minimal Android launcher. Installing the Core APK provides a usable Home screen with an alphabetical app list and app launching. Add-ons provide optional helper functions, integrations, and enhancements.

Core usability never depends on installing or configuring an add-on. See [ADR-0004](../decisions/ADR-0004-complete-core-launcher.md).

## 2. Core requirements

Core shall provide:

- Android HOME/default-launcher integration.
- a production Home screen with an alphabetical app list, icons, letter dividers, side navigation, and tap-to-launch (v2 target; section 19).
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

Core owns the complete everyday launcher, including Home, app discovery, alphabetical ordering, built-in Most Used pins and optional usage suggestions, launching, settings, accessibility, and recovery. Terminal, script runtimes, automation, weather, complex integrations, and alternative presentation are optional add-ons. A separate app drawer is not required: the default Home is the app list.

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

Alphabetical ordering is built into Core. Optional filtering, tagging, grouping, and alternative sorting extend the shared model. Built-in search is a Core requirement (section 15).

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
- Each default row displays an app icon and name and launches the exact component in its profile. Core provides an optional text-only setting and alphabetical letter dividers (section 19). Profile subtitles and add-on badges remain opt-in external presentation. Profile identity remains internal for correct launching and availability checks. Duplicate labels must not collapse distinct activities.
- Disabled or unavailable activities must not appear actionable. Paused profiles and launch failures receive clear feedback without crashing Home or bypassing Android restrictions.
- Package and profile changes refresh the list. Loading, empty catalog, and discovery failure are distinct states; failures offer retry.
- Settings is explicitly reachable from Home. Back from Settings returns to Home. Back on baseline Home leaves Home visible. A HOME intent while Settings is open returns to Home, including reuse of the existing activity.
- Ordinary return from a launched app preserves Home scroll position where possible. Rotation/process recreation restores appropriate UI state; a newly delivered HOME or app-icon intent takes precedence and shows Home.
- TalkBack semantics, large text, adequate touch targets, contrast, and system insets are baseline requirements.
- Optional add-on failure shall retain or restore built-in Home and alphabetical ordering. Settings and add-on disable controls remain reachable.
- No separate drawer is required. Search, built-in themes, alphabet navigation, optional wallpaper, pull-down reachability, and default-off Most Used are Core requirements (sections 15 and 19). Widgets and alternative layouts remain optional enhancements.

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
- Context management supports creation, editing bindings, deletion, activation, and deactivation. Advanced binding edits require deactivation; ordinary module settings actions use the atomic update path in section 18. Failed activation preserves the previous selection. Missing/disabled providers produce a visible degraded state without breaking Home.
- Permission controls display requested, supported Core capabilities and allow explicit grants/revocations using the installed signing identity. Authority is checked live; permissions apply to the package identity, not independently to each service in the same APK.
- “Uninstall” invokes Android's package removal confirmation and explains that every module in the APK is removed. Core cannot uninstall itself through this screen. Cancellation or platform refusal leaves the installed package and saved state intact. Refresh after returning from Android; report actual installed state rather than assuming success.
- Disabling a module, deleting an instance/context, and uninstalling its APK are distinct operations. Uninstall does not silently erase saved instances or contexts; unresolved references remain visible and can be repaired or explicitly deleted.

Acceptance: disable/re-enable and restart Core; retry a failed/blocked handshake; simulate stale callbacks, service death, and an incompatible module; restart and inspect retained events; clear history; edit invalid/referenced configuration; reject an invalid context switch; revoke permission during a session; cancel uninstall and then confirm uninstall. Home and Settings must remain reachable throughout.

## 15. Home controls and themes

These requirements extend the baseline Core and Settings implementation:

- Remove the “Apps” title from Home.
- Keep a persistent, single-line search bar at the bottom of Home, above the system navigation inset, filling the available width beside the settings gear, both with a 48 dp baseline height and adequate touch targets. Tapping or typing must not expand the bar horizontally or vertically; long text scrolls within it to keep the caret visible, and Settings stays accessible. Larger system font settings may increase the minimum content height for accessibility. Keyboard Back dismisses editing before Home navigation. Clearing search restores the full alphabetical list; show an explicit no-results state.
- Use a gear icon with the accessible label “Settings” in place of the text button.
- Provide persisted Light, Dark, and AMOLED themes in Settings and apply them consistently to Home, Settings, dialogs, and system bars. AMOLED uses a true-black background with readable controls and text.
- Define a bounded theme extension contract before adding themes from external add-ons. Losing an add-on theme falls back to a built-in theme without blocking Home.
- Verify compact widths, font scaling, TalkBack, keyboard/insets, and contrast in all built-in themes. Core icons and letter sections follow section 19; profile subtitles remain off by default.

## 16. Delivery and acceptance evidence

The delivered baseline at `3e5559f552d9218c23b67b3b45e73d1f3113839d` implements the earlier text-only Home, top search/gear controls, and Settings behavior and connects the
selected `apps.sort/v1` helper to Home. Core applies search after ordering and
validates helper output against the current catalog. Timeout, failure, disabled
or removed providers, and invalid or stale results retain built-in ordering.
Explicit HOME/app-icon intents reset search; returning from Settings or a launched
app preserves it, and recreation restores the saved query.

The built-in themes and a bounded [future theme contract](../architecture/theme-contract.md)
are included. External theme loading and external Home/Drawer rendering remain
later add-on work, as do automation, scripting, MCP transport, and terminal tools.
They are not dependencies of Core acceptance.

See [Core completion](../implementation/core-completion.md) for automated coverage,
CI evidence, remaining physical-device checks, and the reference helper proof.
Implementation status is not a claim that every OEM/device combination was tested.

## 17. Core framework and remaining acceptance

The Core/module framework now implements the remaining contract requirements:

1. **Per-instance runtime configuration.** Protocol v4 carries the stable instance
   ID and bounded configuration JSON when acquiring a capability. Core and the SDK
   validate the shared versioned schema and defaults. The reference sort helper uses
   per-instance `reverse` settings; old endpoints are revoked on configuration changes.
   Protocol v1–v3 providers retain their original ABI and accept only empty configuration.
2. **Discovery descriptors.** Service metadata points to a bounded raw JSON resource
   containing identity, protocol compatibility, capabilities, configuration schema,
   and optional management information. Core cross-checks it against the runtime
   handshake. Metadata never grants authority.
3. **Public add-on development.** Separately packaged `module-api` and `module-sdk`
   libraries provide the IPC contract, service/session lifecycle, caller verification,
   configuration snapshots, and typed Core clients. An independent sample consumes
   their Maven artifacts without Core source or its signing key.

See the [framework acceptance matrix](../implementation/core-framework.md),
[protocol v4](../architecture/addon-protocol-v4.md), and
[add-on development guide](../addon-development.md) for implementation and evidence.
Automated verification is recorded per commit; implementation alone does not close
physical-device acceptance. TalkBack, work-profile transitions, process recovery,
keyboard/system insets, HOME role, and Android uninstall cancellation/success still
require the device checklist in core-completion.md.

External presentation/theme loading, richer configuration schemas, automation, scripts,
MCP transport, and terminal remain later features. The theme-extension design is a
contract proposal, not an implemented external theme loader.

## 18. Module settings for ordinary use

The Most Used add-on statements in this section record the earlier reference
implementation. Current built-in Most Used setup is in
[ADR-0006](../decisions/ADR-0006-core-most-used-and-feature-order.md); its
Usage Access grant targets Core.

- Every discovered module has a visible **Settings** action in Core. Show an explicit
  unavailable, connecting, incompatible, or no-settings state instead of an empty screen.
- Core renders the validated descriptor's flat settings as ordinary controls: switches,
  bounded number fields, text fields, and enumerated choices. Normal setup requires no
  JSON, manual instance creation, capability identifiers, or context creation.
- A sort helper's **Use on Home** action validates and saves its settings and selects its
  ordering atomically. **Save settings** updates active settings without manual deactivation.
  Failed changes preserve the previous working configuration. **Use alphabetical order**
  restores the built-in order. Advanced instance/context tools remain optional.
- A module's **Permissions and settings** action opens only its declared, revalidated
  exported management Activity within the installed provider package, including when its
  service is unavailable. Android special access must be explicitly approved by the user.
- Both official add-on launcher screens show readable controls and a **Configure in
  Modular** action which opens that module's Core settings. Most Used visibly exposes
  Usage Access and distinguishes denied access, no history, and unavailable history.
- Most Used must demonstrate real foreground activity ranking above an unused alphabetical
  fixture in cross-APK acceptance. Zero-history alphabetical output alone is insufficient.

See [Module settings](../implementation/module-settings.md) for UI and acceptance evidence.

## 19. Reference-led Home presentation and featured apps (source implementation; acceptance pending)

The Most Used ownership portion below is superseded by [ADR-0006](../decisions/ADR-0006-core-most-used-and-feature-order.md): Core owns the built-in toggle, pins,
usage suggestions, and optional Usage Access; the generic featured contract remains.

The 2026-11-07 product request supersedes the text-only/add-on-only icons and
alphabet-index rules. The normative detail is in [Home presentation v2](home-presentation-v2.md),
with [implementation sequencing](../implementation/home-presentation-v2-plan.md)
and [ADR-0005](../decisions/ADR-0005-indexed-home-and-featured-addons.md).

- Core: app icons enabled by default, optional text-only setting, letter dividers,
  locale-aware right-side alphabet navigation with curved drag feedback and a
  selected-letter bubble, reference-aligned typography/spacing, optional system
  wallpaper/dimming, bottom persistent search/gear controls, and retained
  pull-down reachability that brings the first app to mid-screen.
- Built-in Core Most Used: an optional starred section and star rail destination, ordered pins
  before usage-ranked suggestions, ordinary pin/unpin/reorder controls, and
  persistent exact-identity pins that also work without Usage Access.
- Core always retains the complete alphabetical catalog. Featured entries may
  also appear there; search deduplicates. A new bounded featured-app capability
  carries data/actions without changing the existing sort ABI or injecting UI.
- Existing module management, diagnostics, grants, per-instance settings, failure
  recovery, and offline Core guarantees remain in force.

These are new acceptance requirements, not additions to the previous delivery's
passed-test claims. V2-A1 through V2-A10 in the detailed specification must be
verified as implementation proceeds.

## 20. Populated navigation, app actions, and cached discovery

The later 2026-11-07 request supersedes the empty-letter rail behavior. Only
populated locale sections appear; this includes Æ, Ø and Å when applicable.
Core search matches both displayed app names and package identifiers. Core owns
the long-press management sheet, Android App info/uninstall handoff, persistent
display-name overrides and hiding/restoring apps. Hidden identities are omitted
from all Home presentations without altering the raw catalog exposed to granted
capabilities. Built-in Most Used ranking and pins are Core functions.

Core uses a private disposable app-index snapshot to display cached metadata
before a background cold-start reconciliation. Normal package events update
only affected packages; profile/locale changes and explicit refresh reconcile
the full catalog. Detailed contracts and exclusions are in
[HOME-V2-03/04/08](home-presentation-v2.md).

Acceptance: sparse Norwegian rail before/after last-entry removal; search
`Nvidia` for `com.nvidia.shieldtv` (including Turkish device locale); long press
without add-ons; rename/reset/restart; hide/search/star exclusion and restore;
Android uninstall cancellation; invalid cache and profile/locale mismatch;
incremental update/removal; unchanged generations after identical refresh.
