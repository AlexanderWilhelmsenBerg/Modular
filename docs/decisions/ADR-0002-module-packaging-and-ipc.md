# ADR-0002 — Module Packaging and IPC

Status: **Accepted direction; detailed protocol pending**

Date: 2026-10-02

## Context

Modular requires modules that can be added/removed independently, updated separately, isolated from Core failures/dependencies, and potentially authored by third parties.

Two broad Android options are:

1. build-time / Play Dynamic Feature modules;
2. independently installed APKs communicating over Android IPC.

## Decision

Use **independently installed Android APKs** as the primary plugin model.

Use **Binder/AIDL** for Core-to-module IPC.

Do not use Play Dynamic Feature modules as the public plugin architecture.

Do not load arbitrary plugin DEX/native code into Core.

## Why not Dynamic Feature Modules?

Google Play Feature Delivery can install feature modules on demand, but those modules are parts of the same app bundle and depend on the base application.

That is useful for modular delivery of one application, but it does not satisfy the primary goal of an open, independently installable module ecosystem.

Dynamic features remain an implementation option for private/bundled recovery features, not the public plugin contract.

## Why Binder/AIDL?

Android's AIDL is designed for a client and service in different processes/applications to agree on an IPC interface.

It provides:

- Android-native process lifecycle;
- Binder identity;
- generated marshalling;
- callbacks;
- a versionable boundary;
- Kotlin/Java implementation support.

The Android application sandbox then provides process/UID isolation between Core and module APKs.

## Public SDK versus wire contract

The SDK may offer idiomatic Kotlin wrappers, coroutines, typed models, helpers, test fakes, and Compose-independent utilities.

The **wire contract must remain the authority**.

A third-party module must not depend on Core dynamically loading Kotlin classes from its APK.

## UI implication

Arbitrary Compose lambdas/classes cannot be the cross-process UI contract.

Embedded Home/Drawer modules therefore need a declarative surface protocol or another explicitly designed remote-surface mechanism.

v0 should prefer a small declarative protocol because it is inspectable, versionable, testable, and compatible with Core-side policy/accessibility rendering.

## Sources

- Play Feature Delivery: https://developer.android.com/guide/playcore/feature-delivery
- Android AIDL: https://developer.android.com/develop/background-work/services/aidl
- Android application sandbox: https://source.android.com/docs/security/app-sandbox
- Android service-binding/process behavior: https://developer.android.com/topic/performance/memory/guide/service-bindings
