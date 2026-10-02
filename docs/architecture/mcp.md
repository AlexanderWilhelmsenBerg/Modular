# MCP Architecture

Status: **Initial architecture**

## 1. Principle

MCP is an external interoperability layer and agent/tool boundary.

It is **not** the internal module IPC mechanism.

Internal module IPC uses Android Binder/AIDL because that matches process lifecycle, identity, and local Android security semantics. MCP is layered above the Core capability router.

## 2. Why Kotlin

The official Model Context Protocol Kotlin SDK supports clients and servers, Kotlin coroutines, typed protocol models, and transports including Streamable HTTP and WebSocket.

This aligns with the Android Core language and avoids adding a second runtime solely for MCP.

## 3. Inbound model

```text
MCP client
    │
    ▼
Core MCP endpoint
    │
    ▼
MCP policy/grant check
    │
    ▼
Capability Router
    │
    ├── Core-safe operation
    └── Module capability
```

Possible tools include:

- `launcher.context.list`
- `launcher.context.current`
- `launcher.context.activate`
- `launcher.modules.list`
- `launcher.apps.search`
- `launcher.apps.launch`

Names are illustrative; the stable MCP namespace is not yet accepted.

## 4. Module MCP contribution

A module may register MCP-facing tools/resources through the Core broker.

The module does not automatically obtain a network listener.

Core owns:

- external endpoint policy;
- authentication/authorization;
- exposure filtering;
- audit metadata;
- mapping remote calls to capability grants.

## 5. Outbound model

Selected modules may act as MCP clients to external servers.

Examples could include home automation, notes, source control, or homelab integrations.

Outbound MCP is a module capability requiring explicit network and connection configuration. It must not be required for the launcher to function.

## 6. Transport direction

Preferred remote transports: Streamable HTTP or WebSocket as supported by the official SDK.

STDIO is useful for desktop/CLI MCP environments but is not the primary Android launcher integration path.

Transport selection remains configurable and separate from capability semantics.

## 7. Security

MCP never expands a module's authority.

For a request to execute, all applicable checks must pass:

1. endpoint/client is authorized;
2. requested MCP tool is exposed;
3. active policy permits the operation;
4. target module/instance is enabled and compatible;
5. target capability is granted;
6. any required interactive confirmation is satisfied.

High-impact categories include at least:

- terminal execution;
- script execution;
- module installation/removal;
- destructive filesystem actions;
- elevated/root/Shizuku operations;
- security/policy changes.

These require stronger policy than ordinary read-only status operations.

## 8. Observability

MCP calls should produce structured audit events containing:

- timestamp;
- client identity where available;
- tool/capability;
- target module/instance;
- authorization decision;
- result category;
- duration;
- correlation ID.

Secrets and sensitive payload bodies must not be blindly logged.

## 9. Initial MCP milestone

Do not begin MCP implementation until the local capability/module boundary is demonstrated.

First MCP proof:

- expose `context.current`;
- expose `context.list`;
- expose a policy-approved `context.activate`;
- route at least one tool contributed by a reference module;
- prove denied capabilities cannot be invoked remotely.
