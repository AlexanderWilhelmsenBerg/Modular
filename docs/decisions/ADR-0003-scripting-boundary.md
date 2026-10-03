# ADR-0003 — Automation and Scripting Boundary

Status: **Accepted architecture direction**

Date: 2026-10-03

## Context

Modular is intended to support powerful user automation, including small MCP-generated convenience scripts and larger local data-processing tasks.

Examples include:

- organizing downloaded PDFs from a launcher quick action;
- checking a webpage daily and notifying on change;
- recording a location when a chosen Bluetooth device disconnects;
- performing NumPy/dataframe-style local data transformations.

Allowing scripts to call Android APIs directly would duplicate lifecycle, permission, compatibility, and security behavior across runtimes and scripts.

It would also couple script portability to Android framework details.

## Decision

Adopt the following rule:

> **Modules interact with Android; scripts orchestrate module capabilities.**

Scripts are distinct from modules.

Python packages/modules are also distinct from Modular modules.

The first planned Script Runtime is Python/CPython, but the scripting contract is runtime-neutral and must permit Lua, JavaScript, WASM, or other future runtimes.

The Automation engine, trigger providers, and Android-facing capability providers remain modules.

## Consequences

A Python runtime may execute sophisticated logic and data processing, but Android operations occur through capability proxies.

Examples:

- filesystem access → Storage/File capability;
- HTTP → Network/HTTP capability;
- Bluetooth disconnect → Bluetooth trigger capability;
- current location → Location capability;
- notification → Notification capability;
- schedule → Scheduling/Automation capability;
- launcher quick action → Home/Quick Action capability.

This keeps Android permissions and lifecycle behavior in Kotlin/Android modules while allowing scripts to remain compact and generated dynamically.

## MCP

MCP is expected to be a primary authoring path.

An MCP server may inspect runtime/capability schemas, generate script source and manifests, validate them, and request installation/update.

Generated scripts remain inspectable user artifacts.

MCP cannot bypass capability grants merely because it authored the script.

## Android background behavior

Python must not implement recurring automation by remaining alive, sleeping, or polling indefinitely.

Android-facing scheduling/event modules wake or invoke the automation/script runtime when work is due.

Likewise, Bluetooth and location behavior belongs to modules capable of handling the relevant Android permissions and background-execution restrictions.

## Python dependencies

The Python host may provide optional runtime profiles.

A lightweight default environment should not absorb the installation/storage cost of large numerical/data packages.

Large packages such as NumPy/dataframe tooling may be offered through an optional Data runtime/profile when compatible Android builds and acceptable packaging are verified.

Unrestricted arbitrary `pip install` is not required for the first Python runtime.

## Rejected alternatives

### Scripts call Android APIs directly

Rejected because each runtime would need Android-specific bindings, permission behavior, lifecycle handling, OS-version handling, and security review.

### Python is itself a Modular module language

Rejected as the primary scripting concept.

A separately installed Android APK may theoretically be authored with Python-oriented tooling, but that is independent from the Script Runtime architecture.

### Scripts live in Core

Rejected because script runtimes and generated code should not share Core's process/security boundary.

## Related

See:

- `docs/architecture/automation-and-scripting.md`
- `docs/architecture/security.md`
- `docs/architecture/module-model.md`
