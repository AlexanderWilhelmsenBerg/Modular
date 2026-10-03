# Security Model

Status: **Initial security principles**

## 1. Trust boundary

Every external module APK is outside Core's process and UID trust boundary.

Installation does not imply unrestricted authority.

Core treats module identity, requested capabilities, and granted capabilities as separate concepts.

## 2. Capability grants

Modules declare what they provide and what Core/system access they require.

Core records explicit grants.

Candidate categories:

### Low impact
- read current context;
- consume public launcher app model;
- provide sorting/filtering;
- render declarative surfaces.

### Medium impact
- launch applications;
- request context activation;
- network/MCP client access;
- receive broader launcher events.

### High impact
- execute scripts;
- execute terminal commands;
- install/remove modules;
- modify security policy;
- use elevated providers;
- expose remotely invokable write actions.

The final category model and UX are **Open**.

## 3. Android sandbox

Separate APK modules intentionally preserve Android's UID-based application sandbox.

Core must not defeat this by loading untrusted module DEX/native code into the Core process.

Secrets remain in the narrowest owner possible.

## 4. Binder identity

Core must verify the identity of connected module packages and modules must be able to verify legitimate Core calls.

The exact caller/package/signing-certificate strategy requires a dedicated implementation spike because independently signed community modules cannot rely on a same-signature permission model.

Do not ship a public exported Binder service that trusts only an intent action string.

## 5. Declarative UI

Launcher-embedded module UI is data, not executable code in Core.

Core validates:

- schema;
- size/depth limits;
- supported node types;
- referenced actions;
- URI/resource policy.

This prevents a surface module from becoming an arbitrary in-process code-loading mechanism.

## 6. Terminal and scripts

Terminal/script modules are high-impact by design.

Core shall distinguish execution backends and their authority.

An ordinary sandboxed terminal cannot be presented as device-wide shell access.

If a future Shizuku/root/ADB provider exists, its elevated authority must be explicit, revocable, and independently auditable.

## 7. MCP

Remote MCP access is an additional trust boundary.

A capability being locally available does not mean it is remotely exposed.

High-impact calls may require interactive confirmation, policy allowlists, or be unavailable remotely.

## 8. Failure behavior

Security failures fail closed:

- unknown module → unavailable;
- incompatible protocol → unavailable;
- missing grant → denied;
- stale/invalid session → reconnect/re-authorize as required;
- malformed payload → reject;
- ambiguous caller identity → reject.

The launcher should continue to provide a recovery/settings path when possible.
