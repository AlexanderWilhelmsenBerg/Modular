# Delivery Roadmap

This is sequencing, not a release-date commitment. Target requirements are defined in the product specification and ADR-0004; listing a feature here does not mean it is implemented.

## Phase 0 — Align the product boundary

- Complete Core launcher; optional helper add-ons.
- Define Home/navigation, app identity, accessibility, and recovery acceptance.
- Preserve versioned IPC and security contracts for external add-ons.

## Phase 1 — Complete and reliable Core (delivered baseline)

- Built-in Home with one vertical text-only app list.
- Built-in locale-aware alphabetical ordering and exact activity/profile launching.
- Non-blocking Android HOME role request.
- Settings navigation and cold/warm HOME intent handling.
- Loading, empty, unavailable-profile, and failure states.
- Package/profile updates, rotation, process recreation, and scroll restoration.
- TalkBack, large text, touch targets, contrast, and system insets.
- Default configuration without external modules or manual context creation.

Acceptance: install only Core offline; open Home, launch apps, use Settings/Back/HOME, and pass product-spec acceptance cases 1–5. No external surface protocol is a prerequisite.

## Phase 1b — Optional add-on reliability

- Complete Settings controls for persisted enable/disable, retry, event history, Android uninstall, instance/context editing, and live permission grants.
- Validate existing registry, routing, grants, events, and instance infrastructure.
- Demonstrate an optional helper such as alternative sorting/filtering.
- Retain the existing Alphabetical Sort APK as an optional IPC contract example.
- Handle provider death/removal, timeouts, invalid output, incompatible updates, and revocation with usable built-in Home.
- Validate context activation atomically; preserve the previous usable configuration on failure.

Acceptance: product-spec cases 6–8; external add-ons remain separately packaged and subject to live grant checks.

## Phase 1c — Home controls and built-in themes (delivered baseline)

- Remove Apps heading; persistent full-width search and accessible settings gear.
- Persist Light, Dark, and AMOLED themes across Home and Settings.
- Preserve default text-only rows and baseline navigation/accessibility.
- Design an add-on theme contract before external themes are loaded.

These requirements are implemented in the Core completion change. Validation and device acceptance are tracked in [Core completion](implementation/core-completion.md).

## Phase 1d — Core add-on framework

- Protocol v4 carries bounded, validated instance identity and configuration.
- Packaged discovery descriptors are validated against the runtime handshake.
- Public API/SDK AARs support independently signed add-ons and a standalone example.
- Automated acceptance covers two configurations of one provider, incompatible
  descriptors, caller trust, revocation, and alphabetical fallback.

Implementation and commit-specific verification are tracked in
[Core framework acceptance](implementation/core-framework.md). Physical-device
acceptance remains open; see product-spec section 17 and the device checklist.

## Phase 1e — Reference-led Home presentation v2 (next; planned)

Requirements: [Home presentation v2](product/home-presentation-v2.md).
Implementation packages: [v2 plan](implementation/home-presentation-v2-plan.md).

1. P1: Core icons, optional wallpaper/dimming, locale letter sections, bottom search/gear.
2. P2: animated side alphabet and retained pull-down reachability.
3. P3: bounded featured-app capability/SDK and generic Core section/pin controls.
4. P4: Most Used star destination, persistent manual pins, usage suggestions, safe migration.
5. P5: integrated visual, gesture, cross-APK, failure and physical-device acceptance.

P3 contract work can run alongside P1; P2 uses P1's geometry and section model;
P4 depends on P3. All ordinary setup remains in module Settings. This phase
supersedes the baseline text-only/icon and alphabet-index boundary without
claiming these features already exist. Pinning/ranking remain in the add-on.

## Phase 2 — Module UX and richer composition

- richer nested schemas and configuration controls beyond the implemented flat fields.
- bounded external surface protocol and optional alternative Home/Drawer modules.
- additional favorites/grouping providers and alternative layouts beyond phase 1e.
- richer instance configuration.
- richer installation/distribution flows beyond Android uninstall.
- compatibility UX.
- version/upgrade handling.
- richer capability descriptions and configuration schemas.
- extended diagnostics beyond the local module event history.
- module crash/death recovery UX.
- richer filtering/sorting/search capabilities.

## Phase 3 — Automation foundation

- Automation module/capability.
- trigger contract.
- action/workflow model.
- scheduling provider.
- event provider model.
- conflict/debounce/retry policy.
- manual override semantics.
- execution history.

First trigger examples:

- manual/quick action;
- time/schedule;
- Bluetooth event.

First Android-facing action capabilities should include enough to prove file, network, notification, and location composition.

## Phase 4 — Script runtimes

### Python first

- Script Runtime contract.
- Python/CPython Runtime module.
- Modular Python SDK/capability proxies.
- script manifest.
- source/config/state storage.
- validation and dry-run.
- timeouts/cancellation/logging.
- lightweight default dependency profile.
- optional Data profile after package/build-size validation.

### Acceptance automations

1. Quick action to organize PDF files in Downloads through a File module.
2. Daily webpage-change checker through Schedule + HTTP + State + Notification capabilities.
3. On selected Bluetooth disconnect, request and store one location fix through Bluetooth + Location capabilities.
4. A non-trivial local data transformation demonstrating optional NumPy/dataframe support where available.

### Later runtimes

The runtime contract must permit:

- Lua;
- JavaScript;
- WebAssembly/WASI;
- other runtimes without changing Core.

## Phase 5 — MCP authoring and control

- official Kotlin MCP SDK integration.
- policy model and audit trail.
- capability/schema discovery.
- context read/activation tools.
- module-contributed MCP tool proof.
- script generation workflow.
- script validation/install/update workflow.
- authentication.
- outbound MCP-client module proof.

Primary scripting goal:

> A user can describe an automation in natural language; MCP can inspect available capabilities, generate an inspectable script + manifest, validate required grants, and install it after user approval.

## Phase 6 — Terminal and elevated providers

Only after the security/capability model is proven:

- Terminal capability/module.
- sandboxed backend first.
- optional ADB/Shizuku/root providers only through separate ADRs.
- explicit policy for remotely invoked terminal actions.

Terminal is not the Python scripting architecture and must not become a shortcut around capability grants.

## Later candidates

- icon/theme modules;
- widget integration;
- richer trigger providers;
- module catalog/distribution;
- optional desktop module-development/test tooling;
- additional script-runtime profiles;
- isolated execution for untrusted third-party scripts.


