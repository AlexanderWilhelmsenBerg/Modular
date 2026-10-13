# Security Model

Status: **Phase-1 base security/grant infrastructure implemented; richer policy UX remains open**

## 1. Trust boundary

Every external module APK is outside Core's process and UID trust boundary.

Installation does not imply unrestricted authority.

Core treats module identity, requested capabilities, and granted capabilities as separate concepts.

## 2. Capability grants

Modules declare what they provide and, from module protocol v3 onward, which
Core capabilities they request.

Providing a capability and requesting Core authority are separate concepts.

A request is never authority by itself. Core records explicit, persistent
grants bound to the module's exact installed package name plus current APK
signing-certificate SHA-256 identity.

An effective grant is the intersection of:

- a valid protocol-declared request;
- a capability supported by this Core version;
- the persistent grant record for the exact current module identity.

A signing-identity change invalidates previous grants and requires a new
authorization decision.

For official same-signer modules, Phase 1 may initialize a new identity record
with an explicit bootstrap grant set, but only for capabilities requested at
that first initialization. Once the record exists, revocation is sticky and
later module updates cannot silently bootstrap newly requested authority.

Grant checks are live. Core re-checks the current signer and current grant state
when a host capability is enumerated, when its Binder is acquired, and on every
call through an already-issued capability Binder.

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

Core's Phase-1 host session verifies Binder caller UID ownership of the expected
package and separately binds grant authority to that package's current signing
certificate digest.

This allows independently signed community modules without treating an intent
action string, package name alone, or installation alone as authority.

A future public distribution model may add signer-lineage migration UX, but
Phase 1 intentionally fails closed on a changed current signer rather than
silently carrying grants across signing-key rotation.

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
