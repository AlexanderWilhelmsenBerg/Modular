# Phase 1 — Slice 4: External Sort Capability Canary

Status: **Implemented, pending CI**

## Goal

Prove the Slice-3 capability runtime with a real independently installed Android APK before adding more Core policy layers.

The canary capability is:

`modular.apps.sort/v1`

The first provider is:

`modular.reference.sort.alphabetical`

## Why sorting first?

Sorting is deliberately low-risk:

- no Android platform permissions;
- no launcher lifecycle ownership;
- no persistent module configuration yet;
- deterministic input/output;
- easy to validate independently.

If the capability/session design is wrong, this module should expose that before Home, Drawer, Context, or automation work depends on it.

## Public contract

`IAppSortCapability.sort(AppSortRequestParcel)`

The request contains:

- a bounded list of app keys + labels;
- the locale tag Core wants used for comparison.

The response contains:

- status;
- an ordered list of the exact input keys;
- optional failure message.

The v1 request is capped at 2,000 entries to keep the Binder payload bounded.

A later major version can introduce streaming/chunked sort sessions if real devices demonstrate that this one-shot contract is insufficient.

## Core validation

Core does not trust a sort provider's returned list blindly.

`AppSortResultValidator` requires the response to be an exact permutation of the input:

- same item count;
- no duplicate keys;
- no unknown keys;
- no missing keys.

A bad provider therefore cannot inject an app identity or silently drop one through the sort result.

## Reference Alphabetical Sort APK

The reference module is a separate Android application:

`reference-sort-alphabetical`

It:

- exports only the Modular module service;
- speaks module protocol v2;
- advertises only `modular.apps.sort/v1`;
- creates a per-session capability Binder;
- checks that the caller uses the trusted signer;
- links the Core host Binder to death;
- revokes the capability Binder when the session closes or Core dies.

The sorting implementation is locale-aware using `Collator`.

Stable tie-breakers are:

1. original label text;
2. package name;
3. activity class;
4. profile serial.

The module never queries Android packages or launcher state.

## Core client

`AppSortClient`:

1. receives a resolved external route;
2. asks the Capability Router for the module capability Binder;
3. casts it with `IAppSortCapability.Stub.asInterface(...)`;
4. sends app keys/labels;
5. validates the returned permutation;
6. maps the keys back to Core's existing `LaunchableApp` objects.

Core therefore remains the owner of app identity and app metadata.

## Real cross-APK smoke test

Slice 4 adds an Android instrumentation smoke test which runs on an emulator.

CI:

1. builds Core and the Alphabetical Sort APK;
2. installs the reference module as a separate application;
3. installs/runs the Core instrumentation APK;
4. lets `ModuleRegistry` discover the module through the normal service action;
5. performs the protocol-v2 session handshake;
6. lets `CapabilityRouter` publish `modular.apps.sort/v1`;
7. acquires the capability Binder;
8. invokes the sort through real Binder IPC;
9. verifies the resulting app order.

This is the first CI test which exercises the actual separate-package/process module boundary.

## CI gates

The normal verification job covers:

- module-api unit tests;
- Core unit tests;
- Alphabetical Sort unit tests;
- lint for all three modules;
- Core debug APK assembly;
- reference module debug APK assembly.

A second `ipc-smoke` job runs only after the normal verification job succeeds.

It uses an Android emulator with a stable Android 15 system image while installing the API 37 compile platform.

## Deliberately deferred

Slice 4 still does not add:

- provider selection persistence;
- module instances;
- Context Manager;
- user-managed grants;
- community-module approval;
- Home surface capability;
- Drawer surface capability;
- Surface Protocol.

Those become the next Core work after this canary proves the runtime.

## Acceptance

Slice 4 is accepted when:

- the reference APK compiles independently;
- alphabetical behavior and Core result validation pass unit tests;
- normal lint/build checks pass;
- the emulator smoke test discovers the installed module;
- protocol v2 opens successfully;
- the route for `modular.apps.sort/v1` appears;
- Core acquires the external capability Binder;
- an actual cross-process sort invocation returns the expected order.
