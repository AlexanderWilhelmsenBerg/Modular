# Core completion scope and validation

The user requested completion of all Core product requirements, including MCP
brokering, after correcting Slice 8. Slice 8 fix 50ed456d passed full Android CI
38913780111; subsequent Core work is in PR #9. Neither PR has merge authorization.

## Implemented product requirements

| Requirement | Implementation / evidence |
| --- | --- |
| Android HOME integration | HomeRoleController and MainActivity; existing launcher-role UI |
| Settings and recovery | Core Compose shell remains available when modules are missing or invalid |
| Module discovery / compatibility | Versioned AIDL metadata/session handshake; exact capability majors; bounded metadata |
| Module enable / disable / active removal | Persistent component policy, immediate Core-host revocation, unbinding, and atomic removal of one context capability binding |
| Module-instance configuration | InstanceStore, atomic composition setup, profile setup and advanced per-instance JSON editor |
| Capability registration / routing | Generic advertised capabilities and CapabilityRouter; no reference module IDs in Core |
| Connection lifecycle | Binder-death tracking, revocable sessions, bounded provider calls, reconnect/refresh diagnostics |
| Launcher event bus | Existing CoreEventBus and CoreEventSources |
| Persistent state | DataStore composition/grants, module policy preferences and MCP policy/audit preferences |
| Context creation / selection / activation | Atomic Home/Drawer/Sort setup; manual switching; rename; atomic context and unused-instance removal |
| Security and capability grants | Signing-bound authorization, explicit allow/revoke controls, live catalog/launch checks, reviewed bootstrap only |
| App discovery / launching | Existing LauncherApps repository; new Drawer requests route launch through the granted Core host |
| MCP brokering | Authenticated loopback SDK endpoint, explicit mutation exposure, individually approved module tools/resources and bounded audit history |

The 799e0c9a checkpoint passed full Android CI 39035924085, including ten device
tests. A subsequent completion audit added per-context capability removal;
consult PR #9 for that final HEAD's validation rather than applying older evidence.
Composition and grant DataStores have one process-owned file owner; Activity
recreation cancels only observers, avoiding an overlapping asynchronous close/reopen.
See PR #9 for exact-HEAD CI state. The initial Drawer milestone afb6bb4 passed
verification but its new emulator test was rejected by JUnit because Kotlin
inferred a non-void return type. The test now explicitly uses runBlocking<Unit>.

Manage Core can remove one capability from one context without disabling the
installed module or deleting other contexts. The same atomic transaction removes
the instance only if no remaining binding uses it. Editing the active composition
deactivates it and returns to recovery; the user explicitly chooses activation
afterwards. Unit and cross-APK tests cover isolation and continued provider use.

## Reference composition

Home and Drawer remain separate APKs. The Drawer consumes a bounded snapshot
ordered by the selected external Sort provider; it owns labels, profile filtering,
paging and opaque actions. Its session reacquires the live Core launch capability
on each action. Work and Personal can have distinct instances/configurations.
Core does not load module code, own a production drawer, or hardcode reference IDs.

## MCP setup and security

The endpoint is off after every application start. In Core settings choose Start
local MCP, then copy its fresh pairing token. Use a local MCP client with
Authorization: Bearer <token> against http://127.0.0.1:8765/mcp. A desktop may use
an explicitly configured adb forward tcp:8765 tcp:8765. No LAN listener, remote
deployment, port forwarding or external client connection is created by this work.

Stopping revokes the token; restarting rotates it. Tokens are not persisted,
backed up, placed in URLs or written to audit logs. Browser origins and unexpected
Host headers are rejected; the request-body limit is 64 KiB. Read-only Core tools
and the current-context resource are available to authenticated clients. Context
activation and app launch need explicit local exposure toggles. Installation,
policy mutation, terminal, scripts and arbitrary Binder calls have no remote tool.

Modules advertise modular.mcp.tools/v1. The broker accepts bounded descriptors
with primitive argument schemas and a readOnly declaration, then requires local
approval for each export. Approval binds signing identity, Android package update generation, module version,
instance and the full descriptor. The declaration is a module claim, not a proof
about the module's internal behavior; the approval dialog makes that explicit.
Current grants, active composition, route availability, identity and schema are
checked again on invocation. Zero-required-argument approved reads also appear as
MCP resources. The Basic Drawer contributes drawer.status as the reference proof.
Revocation or a changed descriptor requires new approval.

Audit retains the last 128 attempt/outcome events with a shared correlation ID,
credential principal, timestamp, tool, target module/instance, decision and duration. It omits tokens,
argument bodies, result bodies and app labels. Policy and audit survive restart;
the endpoint and credential do not. A bound provider call that times out keeps its
slot until the native transaction returns. Actions are not automatically retried:
a timeout can mean an unknown result rather than cancellation.

## Dependency choice

Official Kotlin MCP SDK server 0.9.0 and Ktor 3.2.3 match Kotlin 2.2 metadata and
serialization 1.9 used by this Android project. SDK 0.15.0 uses Kotlin 2.4; it was
not silently substituted into the current toolchain.
The pinned SDK negotiates MCP 2025-06-18 (or its supported older revisions);
clients must use the version returned by initialize in subsequent HTTP headers.
The test uses the SDK's protocol constant rather than assuming a newer revision.
DNS rebinding protection,
origin/host validation and authentication are explicitly enabled rather than
relying on that older SDK's defaults. Each stateless request explicitly closes
its official SDK server/session because the 0.9 convenience route omits cleanup.
Unsupported argument-schema constraints are rejected rather than silently ignored.
Android compatibility must pass the real
HTTP instrumentation test; it is not assumed from the SDK's JVM sample.

Sources:
- https://github.com/modelcontextprotocol/kotlin-sdk/tree/0.9.0
- https://github.com/modelcontextprotocol/kotlin-sdk/blob/0.9.0/gradle/libs.versions.toml
- https://github.com/modelcontextprotocol/kotlin-sdk/blob/0.9.0/kotlin-sdk-server/src/commonMain/kotlin/io/modelcontextprotocol/kotlin/sdk/server/KtorServer.kt

## Deliberate product boundaries

Automation logic, schedulers, script runtimes, terminals, elevated providers,
rich Home design and an app marketplace remain external modules/later roadmap
work. They are not silently embedded in Core to satisfy this milestone.
Distribution signing, release publication, actual device installation and merge
remain separate from implementing and reviewing the Core app. The broker is an
opt-in local endpoint; WAN access and multi-client OAuth are not shipped by this
local milestone. Future module-specific configuration schemas can replace the
advanced JSON fallback without changing Core ownership.

## Validation

Thirty focused JVM tests passed locally using cached Kotlin/coroutine/JSON
dependencies. These cover the Slice 8 atomic setup, Surface validation, Drawer
paging/profile isolation, MCP approval/argument policy and native-call timeout
slot retention, plus atomic composition creation/removal and shared-instance
isolation. This supplements, rather than replaces, the repository's full
Gradle verification and device tests at the final published SHA.
