# Automation and Scripting Architecture

Status: **Initial architecture**

## 1. Principle

Modular scripting is intended to provide **Tasker-like automation with substantially more programmable logic**, while preserving the module/security architecture.

The governing rule is:

> **Modules interact with Android. Scripts orchestrate module capabilities.**

A script is not an Android application, service, broadcast receiver, permission owner, or launcher module.

A script runtime must not expose Android framework APIs directly to scripts as the normal programming model.

## 2. Separation of concerns

### Android-facing modules

Modules own platform integration and Android lifecycle concerns.

Examples:

- filesystem/document access;
- HTTP/network requests;
- Bluetooth event observation;
- one-shot/location tracking;
- scheduling;
- notifications;
- launcher buttons/actions;
- application launching;
- clipboard;
- media;
- contacts/calendar integrations;
- device-state observations.

Modules obtain Android permissions and implement Android-specific background/lifecycle behavior.

### Automation layer

The automation layer connects triggers to actions/scripts.

It owns concepts such as:

- trigger;
- conditions;
- workflow/automation;
- execution policy;
- retry;
- debounce;
- timeout;
- history;
- result;
- manual execution.

The automation engine is a module/capability, not launcher Core.

### Script runtime

A script runtime executes user-authored or MCP-generated logic.

Initial desired runtimes include:

- Python;
- Lua;
- JavaScript;
- WebAssembly/WASI where useful later.

A runtime exposes a stable Modular scripting API whose operations are brokered capability calls. Runtime choice does not change Android authority.

## 3. Script versus module

A **Module** is an independently installed Android package implementing Modular capabilities.

A **Script** is data/code executed by a Script Runtime module.

A **Python package/module** is a Python dependency or reusable Python code loaded by the Python runtime.

These are distinct concepts.

Example:

```text
Bluetooth Module       Android-facing APK
Location Module        Android-facing APK
Automation Module      trigger/workflow engine APK
Python Runtime Module  CPython host APK

parking-location.py    script
pandas                 Python package
numpy                  Python package
```

## 4. Script execution model

A script receives:

- immutable event/trigger input;
- explicit configuration;
- explicit capability handles/proxies;
- private script storage;
- structured logging;
- cancellation/deadline signal.

It does **not** receive unrestricted Core internals or implicit Android permissions.

Conceptual Python:

```python
async def run(ctx):
    pdfs = await ctx.files.find(
        root="downloads",
        mime_type="application/pdf",
    )

    for item in pdfs:
        await ctx.files.move(item, "downloads/PDF")
```

The `files` object is a Modular capability proxy. It is not Python calling Android storage APIs.

## 5. Script manifest

Each installed script/workflow should have a manifest separate from source code.

Conceptual example:

```yaml
id: local.organize-download-pdfs
name: Organize downloaded PDFs
runtime: python
runtime_version: ">=3.13"
entrypoint: main.py

triggers:
  - type: manual
    action_id: organize-pdfs

requires:
  - files.query
  - files.move

dependencies: []
network: false
```

A scheduled page checker might declare:

```yaml
triggers:
  - type: schedule
    every: 1d

requires:
  - http.fetch
  - state.read
  - state.write
  - notifications.post
```

Trigger syntax is illustrative and not yet the accepted wire schema.

## 6. MCP-authored scripts

A primary product use case is natural-language creation of small automations through MCP.

Example:

> I need a quick button that filters all PDFs to its own folder in Downloads.

The MCP workflow should be able to:

1. inspect installed script runtimes;
2. inspect available capabilities and their schemas;
3. determine whether the requested automation is supportable;
4. generate a script and manifest;
5. validate required capabilities;
6. present the requested grants/behavior to the user;
7. install/update the script after approval;
8. optionally create a launcher button/action through an appropriate UI/action module;
9. provide test/dry-run output.

MCP generates/orchestrates the script. It does not obtain hidden Android access.

Generated scripts must be ordinary inspectable artifacts which can be viewed, exported, edited, disabled, versioned, and deleted by the user.

## 7. Example: PDF organizer quick button

User intent:

> Create a quick button that puts all PDFs in Downloads into a PDF folder.

Composition:

```text
Quick Action/Home Module
          │ manual trigger
          ▼
    Automation Module
          │
          ▼
    Python Runtime
      │          │
      ▼          ▼
 files.query   files.move
      │          │
      └────┬─────┘
           ▼
     Storage Module
           │
           ▼
  Android storage APIs
```

Python performs matching/transformation logic. The Storage module performs Android file access.

## 8. Example: daily website change check

User intent:

> Every day, check this page and tell me when it changes.

Composition:

```text
Schedule Module
      │
      ▼
Automation Module
      │
      ▼
Python Runtime
  │       │       │
  ▼       ▼       ▼
HTTP    State   Notification
Module  Module     Module
```

The script may normalize HTML, hash content, use a parser, compare structured data, or perform dataframe analysis.

The scheduling mechanism is Android-facing and is not implemented by Python sleeping in the background.

## 9. Example: remember where a Bluetooth device disconnected

User intent:

> When this Bluetooth device disconnects, save the phone's current location.

Composition:

```text
Bluetooth Module
    │ disconnect event
    ▼
Automation Module
    │
    ▼
Python Runtime
    │ request one-shot location
    ▼
Location Module
    │
    ▼
Android location APIs
```

Android permission/background constraints are owned by the Location and trigger modules. The script only sees whether the capability succeeds, is denied, unavailable, or requires user action.

This distinction is essential because modern Android restricts background location and background execution in ways that vary by OS version and permission state.

## 10. Python runtime

Python is the preferred first scripting runtime.

Initial direction:

- embedded CPython hosted by a dedicated Python Runtime APK;
- Kotlin wrapper around the runtime and Modular capability proxies;
- async-friendly Modular Python API;
- isolated per-script source/config/state;
- dependency profiles rather than unrestricted arbitrary package installation in v1.

Candidate host technology: Chaquopy, subject to implementation spike and licensing/build verification at implementation time.

### Runtime profiles

Potential profiles:

#### Python Standard
- Python standard library where supported;
- Modular SDK;
- common pure-Python utilities.

#### Python Data
Optional larger environment for data work:
- NumPy;
- dataframe library such as pandas/Polars if Android-compatible builds are available and size/performance are acceptable;
- parsing/transformation libraries.

A script must not force every user to install large data-science dependencies merely because another script needs them.

## 11. Additional runtimes

The automation/script contract must not be Python-specific.

A Script Runtime capability should permit other providers:

```text
scripting.runtime
    ├── Python Runtime
    ├── Lua Runtime
    ├── JavaScript Runtime
    └── WASM Runtime
```

Scripts for different runtimes use the same conceptual Modular capability model even if language bindings differ.

## 12. Data-oriented scripting

Python should support automation that is more substantial than glue code.

Examples:

- CSV/JSON/XML normalization;
- bulk file renaming/classification;
- dataframe transforms;
- report generation;
- local statistical analysis;
- webpage extraction/diffing;
- media/library metadata processing.

Long-running or large jobs require execution budgets and should not run synchronously in the launcher UI process.

The runtime/automation modules decide how to schedule and supervise that work.

## 13. Security model

Scripts are code and must be treated accordingly.

Required principles:

- scripts receive only declared/granted capability proxies;
- Android permissions belong to modules, not scripts;
- remote MCP access does not automatically grant script execution;
- generated scripts are inspectable;
- install/update is auditable;
- secrets use dedicated secret capability/storage rather than source literals;
- execution has timeout/cancellation;
- scripts cannot dynamically request arbitrary Core privileges;
- high-impact capabilities can require interactive approval.

CPython itself is **not** considered a hostile-code security sandbox.

The Android UID boundary of the runtime module helps isolate it from Core, but untrusted scripts require additional isolation if the product later supports running third-party code without user trust.

## 14. Script lifecycle

Desired states:

- Draft
- Validated
- Installed
- Enabled
- Disabled
- Failed/Needs attention

An installed script should expose:

- source;
- manifest;
- version/history;
- dependencies;
- granted capabilities;
- triggers;
- last execution;
- recent logs/results;
- run/test control;
- enable/disable;
- delete/export.

## 15. Phase boundary

Phase 1 is the Core launcher and module architecture.

Automation and scripting are later capabilities.

Phase 1 must nevertheless avoid contracts that make this model impossible. Specifically, capability routing, versioning, event delivery, and security grants must be general enough that future automation and script runtimes can compose module capabilities without gaining Android access directly.
