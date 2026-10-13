# Phase 1 — Slice 6: Core Event Bus

Status: **Implemented, pending CI**

## Goal

Add the lifecycle-safe event/invalidation layer required by the Phase-1 Core kernel.

The Event Bus connects existing Core sources without turning Core into an automation engine and without introducing a new external-module authority path before capability grants exist.

Slice 6 covers:

- typed Core events;
- independent event versioning;
- monotonic sequence numbers;
- bounded non-blocking publication;
- explicit drop accounting;
- consumer gap detection;
- state-resync semantics;
- adapters for module, app, profile, composition, and active-context state;
- recovery-screen diagnostics.

## State is authoritative; events are not

The Event Bus is not an event-sourced database.

Authoritative state remains in:

- `ModuleRegistry.modules`;
- `AppRepository.catalog`;
- `CompositionStateStore.state`;
- `ContextManager.active`.

Events are compact notifications/deltas which tell a consumer that relevant state changed.

This gives the bus safe recovery semantics:

1. read the authoritative StateFlow/repository;
2. begin consuming events;
3. when a sequence gap is detected, discard assumptions based on missed deltas;
4. reread authoritative state;
5. continue from the new event position.

A late subscriber does not receive a replay of historical state.

## Versioning

Each `CoreEvent` has:

- a stable string event type;
- an event version, currently `1`.

Each `CoreEventEnvelope` also has an envelope schema version, currently `1`.

This keeps future event schema evolution separate from the Binder module protocol and separate from capability major versions.

Slice 6 is an internal Core contract. It is deliberately not added to `module-api`.

## Event types

### Module topology/state

`core.modules.changed/v1`

Carries stable component identifiers for:

- added module services;
- removed module services;
- changed module records.

A status transition such as CONNECTING → CONNECTED is a change.

### Applications

`core.apps.changed/v1`

Carries app keys for:

- added launchable activities;
- removed launchable activities;
- changed launchable activities.

The event also carries the completed App Repository generation.

The full app record is not duplicated into the event. Consumers reread Core's authoritative app state.

### Profiles

`core.profiles.changed/v1`

Carries persistent profile serial numbers for:

- added profiles;
- removed profiles;
- changed profile state, such as quiet-mode state.

### Composition

`core.composition.changed/v1`

Carries stable IDs for added/removed/changed:

- module instances;
- contexts.

Configuration payload JSON is not copied into the event stream.

### Active context

`core.context.active.changed/v1`

Carries only the previous and current context IDs.

### Active-context health

`core.context.health.changed/v1`

Carries:

- active context ID;
- ready/degraded state;
- structured capability/instance/problem-kind tuples.

This event can therefore report provider loss/recovery without changing persisted user selection.

## Backpressure

Android callbacks and Core state owners must never block on an event subscriber.

`CoreEventBus` therefore has two stages:

```text
publisher
   │
   │ synchronous trySend
   ▼
bounded Channel
   │
   │ serialized delivery actor
   ▼
SharedFlow subscribers
```

Default pending capacity: **64 envelopes**.

If a slow subscriber stalls delivery and the pending queue fills:

- the oldest pending envelope is dropped;
- publication remains non-blocking;
- `droppedBeforeDelivery` increments;
- the monotonic sequence still advances.

This intentionally creates a detectable sequence gap.

The consumer must resync state rather than pretending the missing delta did not matter.

## Sequence contract

Every accepted publication receives one process-local monotonically increasing sequence number.

`CoreEventCursor` classifies an observed envelope as:

- `First`;
- `Continuous`;
- `Gap(expected, actual)`.

The sequence is an in-process continuity marker. It is not persisted across launcher process restarts.

A process restart already requires consumers to reacquire current state, so persisting the sequence would not improve correctness.

## Event-source adapter

`CoreEventSources` observes the existing Core StateFlows.

For each source:

- the first observed value becomes its baseline;
- later values are diffed;
- only non-empty deltas are published.

App catalog snapshots are considered only when:

- generation > 0;
- `isRefreshing == false`.

This prevents a refresh-start marker from becoming an incomplete app-state event.

## Lifecycle

`MainActivity` owns:

- `CoreEventBus`;
- `CoreEventSources`.

On teardown:

1. event-source collectors are cancelled;
2. the bus is closed;
3. composition/router/registry/repository components are closed.

The bus does not keep module APKs bound.

It observes Core state only.

## Recovery diagnostics

The minimal recovery/settings UI shows:

- latest published event sequence;
- latest delivered event sequence;
- number of envelopes dropped before delivery.

If drops are non-zero, the UI states that sequence gaps require a state resync.

This is diagnostic visibility, not a production event-inspection interface.

## Security boundary

Slice 6 does **not** expose the Event Bus across Binder.

An installed module therefore gains no new visibility merely because the bus exists.

Future module event subscriptions require a separate broker/contract layered on top of:

- module identity;
- capability grants;
- subscription lifecycle;
- allowed event classes;
- resync protocol.

Those rules belong after the base security/grant infrastructure.

Automation also remains outside Core.

A future automation/trigger module may consume explicitly granted event capabilities, but the Core Event Bus itself contains no scheduling, rules, conditions, retries, or actions.

## Tests

JVM tests cover:

- monotonic sequence assignment;
- bounded pending queue;
- drop accounting;
- cursor gap detection;
- cursor reset;
- app add/remove/change delta generation;
- profile add/remove/change delta generation;
- composition ID deltas;
- active-context selection events;
- active-context health/degradation events;
- StateFlow bridge baseline behavior.

Existing Android emulator coverage remains in place for the Core ↔ external module Binder boundary and persisted Personal/Work contexts.

## Deliberately deferred

- external-module event subscriptions;
- AIDL event callback contracts;
- event-specific capability grants;
- event history/persistence;
- automation rules;
- trigger execution;
- permission/grant change events until the grant model exists;
- event filtering policy for external clients.

## Acceptance

Slice 6 is accepted when:

- event publication is non-blocking and bounded;
- sequences are monotonic;
- queue loss is observable;
- consumers can detect gaps;
- source adapters emit typed deltas from existing Core state;
- completed app generations are diffed coherently;
- active-context provider loss/recovery can produce health events;
- Core lifecycle owns and closes the bus;
- recovery diagnostics expose bus health;
- unit tests, lint, APK assembly, and the existing cross-APK emulator smoke remain green.
