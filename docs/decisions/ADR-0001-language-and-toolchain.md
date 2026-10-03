# ADR-0001 — Language and Toolchain

Status: **Accepted for initial implementation**

Date: 2026-10-02

## Context

Modular needs a language/toolchain that fits all of these simultaneously:

- deep Android launcher/platform APIs;
- long-lived service/Binder IPC;
- modern native Android UI;
- asynchronous state/event streams;
- a public module SDK;
- MCP client/server support;
- maintainable security-sensitive Core code;
- potential future native/terminal components.

The choice should minimize bridges in Core.

## Decision

Use **Kotlin as the normative implementation language** for Modular Core, the official module SDK, and official reference modules.

Use:

- Kotlin/JVM for Android application code;
- Jetpack Compose for Core-rendered UI;
- coroutines + Flow for asynchronous/state work;
- Gradle Kotlin DSL;
- AIDL as the cross-process interface-definition language;
- kotlinx.serialization where a versioned JSON representation is appropriate;
- the official MCP Kotlin SDK for MCP;
- C/C++ or Rust only inside a module when there is a concrete native-code requirement.

Do not adopt Flutter/Dart, React Native/TypeScript, C++, Rust, or Java as the primary Core implementation language.

## Research

### Kotlin

Android explicitly describes its development approach as Kotlin-first and recommends starting new Android apps in Kotlin. Its comparison notes Kotlin-specific AndroidX APIs, coroutines, and Jetpack Compose support.

Jetpack Compose is built around Kotlin.

Android recommends Kotlin coroutines for asynchronous programming, and lifecycle/ViewModel/Compose APIs integrate directly with coroutines and Flow.

The official MCP Kotlin SDK is Kotlin Multiplatform, supplies client and server APIs, typed protocol models, coroutine-friendly APIs, and Streamable HTTP/WebSocket/STDIO transports.

This gives Kotlin the shortest path from Android platform code through UI/state to MCP.

### Java

Java remains fully supported by the Android platform and is interoperable with Kotlin.

It is therefore an acceptable interoperability language for third-party modules.

It is not selected for the official implementation because:

- Compose is Kotlin-centric;
- Kotlin-specific AndroidX/KTX APIs are unavailable directly in Java;
- coroutine/Flow ergonomics are substantially better in Kotlin;
- the official MCP Kotlin SDK naturally fits the chosen stack.

The module boundary should not unnecessarily prevent Java callers where AIDL/JVM APIs make Java compatibility inexpensive.

### AIDL

AIDL is not the application implementation language; it is the Android IPC schema language.

Android documents AIDL specifically for cross-application IPC. AIDL files use Java-like syntax and generate Binder interfaces that can be implemented from Kotlin or Java.

AIDL also imposes an important architectural rule: released interfaces must remain backward compatible. Modular therefore versions the module contract from the start.

### C/C++

The Android NDK/JNI supports Kotlin/Java calling native C/C++ code.

Native code is valuable when:

- integrating an existing native library;
- implementing a real terminal/runtime component;
- performance measurements justify it.

It is not beneficial as the default launcher language. Using it for Core would sacrifice direct Compose/coroutine/Android framework ergonomics and add JNI complexity.

### Rust

Rust is attractive for memory-safe native components and may be a strong future choice for isolated parsing/runtime/terminal internals.

It is not selected for Core because the application is fundamentally an Android framework/Binder/Compose application, while Kotlin already has first-class platform and MCP support. Adopting Rust in Core would add an FFI/build boundary without a demonstrated need.

Rust is permitted behind a module-owned native boundary after an implementation-specific ADR.

### Flutter / Dart

Flutter is a mature cross-platform UI framework, but cross-platform UI is not Modular's hard problem.

The hard problem is Android-specific launcher integration, Binder/service lifecycle, package/profile APIs, independently installed Android modules, and MCP policy.

Choosing Flutter would still require native Android bridges for the architecture-defining operations while introducing another UI/runtime abstraction. It therefore increases boundary count without providing a required product capability.

### React Native / TypeScript

The same reasoning applies more strongly: platform-specific launcher and Binder functionality would live in native modules while Core logic/UI straddles a bridge.

There is no cross-platform requirement that offsets this cost.

### Python / Lua / JavaScript scripting

These are **not Core language candidates**.

A future Scripts module may support one or more embedded runtimes, but scripting is intentionally separated from launcher Core. Runtime selection must be based on sandboxing, packaging size, API design, cancellation, and security requirements.

## Kotlin Multiplatform

Do not make Kotlin Multiplatform a requirement for the initial Android launcher.

However, keep pure protocol/domain packages portable where practical because:

- the official MCP SDK is already multiplatform;
- future desktop tooling or a module-development test harness may benefit;
- protocol models can often remain Android-free.

Adopt KMP only when a second target creates real value.

## Consequences

### Positive

- one primary language across Android platform, UI, state, SDK, and MCP;
- first-class Android documentation/tooling;
- fewer runtime bridges;
- Java interoperability remains available;
- native escape hatch remains possible;
- coroutine model fits Binder/network/event work.

### Negative

- official third-party module examples will be Kotlin-biased;
- AIDL remains a second small schema language;
- native terminal/runtime work may later introduce Rust/C++;
- plugin compatibility must be designed at IPC/schema level rather than relying on Kotlin binary interfaces.

## Sources

- Android Kotlin-first: https://developer.android.com/kotlin/first
- Kotlin for Jetpack Compose: https://developer.android.com/develop/ui/compose/kotlin
- Kotlin coroutines on Android: https://developer.android.com/kotlin/coroutines
- Android app architecture: https://developer.android.com/topic/architecture
- Android AIDL: https://developer.android.com/develop/background-work/services/aidl
- Android JNI guidance: https://developer.android.com/ndk/guides/jni-tips
- MCP Kotlin SDK: https://github.com/modelcontextprotocol/kotlin-sdk
- MCP Kotlin SDK API docs: https://kotlin.sdk.modelcontextprotocol.io/
- Kotlin Multiplatform: https://kotlinlang.org/multiplatform/
- Flutter overview: https://flutter.dev/
