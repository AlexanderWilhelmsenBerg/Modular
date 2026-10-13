# Phase 1 — Slice 5: Persistent Instances and Context Composition

Status: **Implemented, pending CI**

## Goal

Add the first durable policy layer above the capability runtime proven in Slices 3 and 4.

Slice 5 introduces:

- persistent module instances;
- persistent contexts;
- explicit provider selection;
- atomic manual context activation;
- live degradation/recovery when a selected provider disappears;
- recovery/settings presentation of saved instances and contexts.

This slice deliberately does not add Home/Drawer surface protocols or rich configuration UX.

## Model

### Module instance

A module instance is Core-owned durable state:

- stable Core-generated instance ID;
- human-readable name;
- stable provider ID;
- opaque JSON configuration envelope.

The provider ID selects an installed capability provider by its runtime module ID.

Two instances may select the same provider.

For example:

```text
modular.reference.sort.alphabetical
    ├── Personal sort instance
    └── Work sort instance
```

The two instances remain independently addressable even when their current configuration is identical.

### Context

A context is a named set of capability-to-instance bindings.

```text
Context: Personal
    modular.apps.sort/v1 → Personal sort instance

Context: Work
    modular.apps.sort/v1 → Work sort instance
```

A context may contain at most one binding for a given exact capability major version.

Multiple capabilities may point to the same instance when a future provider legitimately implements more than one capability.

## Persistence

Core stores the complete composition state in Preferences DataStore using one schema-versioned JSON envelope.

Current schema version: `1`

The persisted state contains:

- instances;
- contexts;
- active context ID.

Unknown JSON fields are tolerated within a known schema version.

A future schema version is rejected rather than silently interpreted.

Observation-time decode failures surface through `loadError`.

Mutations re-decode the persisted state transactionally and fail instead of overwriting malformed state with defaults.

## Instance Store

`InstanceStore` provides:

- create;
- upsert;
- lookup;
- remove;
- observable instance list.

Instance configuration must be a JSON object.

Removing an instance is rejected while any saved context references it. Core therefore never creates a dangling context through an instance deletion.

## Provider selection

`CapabilityRouter` remains the live catalog of currently invokable routes.

`ProviderSelectionResolver` applies saved policy:

1. find the context binding for a capability;
2. find the referenced module instance;
3. read its stable provider ID;
4. find a live route matching both exact capability and provider ID.

Resolution fails closed when:

- the instance is missing;
- no selected provider route is currently available;
- multiple live routes claim the same selected provider identity and capability.

The last case deliberately reports ambiguity rather than choosing the first package discovered.

## Context Manager

`ContextManager` provides:

- create;
- upsert;
- remove;
- activate;
- deactivate;
- observable saved contexts;
- observable active resolved context;
- last activation error.

Activation validates the entire requested context against the current persistent instance set and the current live capability routes.

Only after every binding resolves does Core write the single `activeContextId`.

A failed activation leaves the previously active context unchanged.

This is the Slice-5 atomicity boundary: context composition is selected as one durable unit, not one capability at a time.

## Live provider loss

Provider availability is runtime state, not configuration state.

If the provider for an already-active context disappears:

- Core keeps the persisted active context ID;
- `ActiveContextState` becomes degraded;
- the affected binding reports `PROVIDER_UNAVAILABLE`;
- Core does not silently choose another provider;
- if the exact provider returns, resolution heals automatically.

This preserves user intent across process crashes, temporary package absence, upgrades, or reconnects.

## Root module session versus module instance

The protocol-v2 root Binder session remains a Core↔provider process/session lifetime contract.

It is not the same thing as a module instance.

Slice 5 stores instance identity and configuration in Core, but does not yet introduce capability-specific configuration-schema negotiation or per-instance remote session APIs.

That richer configuration delivery belongs to Phase 2 unless a surface capability proves it is required earlier.

This separation avoids overloading the security/liveness session with product configuration semantics.

## Recovery UI

Core's recovery/settings screen now shows:

- saved instance count;
- saved context count;
- active context;
- ready/degraded state;
- unresolved capability details;
- DataStore load errors;
- activation errors;
- saved context cards with manual Activate actions;
- saved module-instance identities/provider IDs;
- Deactivate action.

The screen intentionally does not yet provide a rich create/edit form. Programmatic CRUD is complete in this slice; richer user-facing configuration belongs to Phase 2.

## Tests

JVM tests cover:

- persistence-codec round trip;
- unknown-field tolerance;
- future-schema rejection;
- two distinct Work/Personal instances using one provider;
- switching between those contexts;
- failed activation preserving the old active context;
- provider-loss degradation while preserving saved selection;
- referenced-instance deletion rejection;
- duplicate capability-binding rejection.

The existing emulator cross-APK canary is extended to:

1. discover the independently installed Alphabetical Sort APK;
2. prove separate Android UIDs and same-signer bootstrap trust;
3. create two persisted instances of the same provider;
4. create Personal and Work contexts;
5. activate Personal;
6. resolve `modular.apps.sort/v1` through the context;
7. invoke the external sort Binder;
8. activate Work atomically;
9. prove the active binding changed to the Work instance;
10. invoke the same external capability through the newly selected composition;
11. observe the Work active-context ID from DataStore.

## Deliberately deferred

Slice 5 does not add:

- module configuration schemas;
- module-supplied instance editor UI;
- per-instance remote module sessions;
- capability grants;
- automatic context triggers;
- Home surface protocol;
- Drawer surface protocol;
- app filtering;
- fallback provider substitution.

## Acceptance

Slice 5 is accepted when:

- unit tests and lint pass;
- Core and reference APKs build;
- composition state persists through DataStore;
- contexts reject dangling/ambiguous selections;
- failed activation does not replace the current context;
- provider loss degrades instead of silently changing policy;
- two distinct instances of the same module can belong to Personal and Work contexts;
- the emulator test activates both contexts and invokes the real external capability through their resolved policy.
