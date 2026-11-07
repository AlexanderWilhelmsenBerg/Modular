# Build an Android add-on

Modular add-ons are separately installed Android APKs. They expose a service over Binder; Core discovers the service and decides whether to connect it. The [standalone sort example](../examples/standalone-sort-addon/README.md) builds outside the Core Gradle project and uses only the published `module-api` and `module-sdk` artifacts.

## Obtain the API

The version 0.2.0 Android libraries use Maven coordinates:

```kotlin
implementation("io.github.alexanderwilhelmsenberg.modular:module-api:0.2.0")
implementation("io.github.alexanderwilhelmsenberg.modular:module-sdk:0.2.0")
```

Android CI publishes the release API/SDK libraries as the Maven-layout `modular-addon-sdk` artifact. Download it from a successful Android CI run and extract it into a directory such as `sdk-repository`. Point a Maven repository in your add-on's Gradle settings at the extracted directory. The standalone example takes it through `-PmodularSdkRepository=/absolute/path/to/sdk-repository`. This is a local artifact source; no Core source checkout or `project(":module-api")` dependency is needed. Android Gradle Plugin 9.4.1, Gradle 9.6, Java 17, compile SDK 37, and min SDK 29 match this release.

`module-api` contains the AIDL wire interfaces, models, identifiers, and `ModuleDescriptorCodec`. `module-sdk` provides `BaseModuleService`, `ModuleInstanceContext`, and `TrustedCorePolicy`. The wire contract remains authoritative if you implement it without the SDK. Do not load add-on classes into Core.

## Declare the service and descriptor

Export a service with action `io.github.alexanderwilhelmsenberg.modular.action.MODULE`. Place a UTF-8 JSON descriptor in your APK's `res/raw` directory, and reference that resource from the **service** metadata:

```xml
<service android:name=".SortService" android:exported="true">
    <intent-filter>
        <action android:name="io.github.alexanderwilhelmsenberg.modular.action.MODULE" />
    </intent-filter>
    <meta-data
        android:name="io.github.alexanderwilhelmsenberg.modular.MODULE_DESCRIPTOR"
        android:resource="@raw/module_descriptor" />
</service>
```

The metadata key is `ModuleContract.MODULE_DESCRIPTOR_METADATA_KEY`. Use `android:resource`, since Core opens the raw resource; a string metadata value is invalid. The runtime `getDescriptorJson()` response must parse to the same descriptor as the packaged resource. A v4 service whose descriptor is missing, malformed, or disagrees with its handshake is incompatible.

The v1 descriptor shape is:

```json
{
  "schemaVersion": 1,
  "moduleId": "dev.example.sort",
  "moduleVersion": "1.0.0",
  "displayName": "Example Sort",
  "description": "An optional sort helper",
  "coreProtocol": {"min": 4, "max": 4},
  "providedCapabilities": ["modular.apps.sort/v1"],
  "requestedCoreCapabilities": [],
  "instanceConfiguration": {
    "schemaVersion": 1,
    "fields": [
      {"key": "reverse", "title": "Reverse order", "type": "boolean", "default": false}
    ]
  }
}
```

`managementActivity` is optional. If supplied, it must name an Activity in the same APK. The flat configuration schema accepts `boolean`, `string`, and `integer` fields with defaults. String fields can specify `minLength` and `maxLength`; integer fields can specify `min` and `max`; fields can specify an `enum` of values of their type. `ModuleDescriptorCodec.parse()` validates the declaration and `normalizeConfiguration()` validates a JSON object, rejects unknown keys, and fills defaults. The descriptor is limited to 32 KiB, configuration to 8 KiB, and the schema to 32 fields. These settings are ordinary instance data; keep secrets in your APK's private storage and store only safe references in Core configuration.

## Implement the service

Subclass `BaseModuleService`. Supply `descriptorJson` by reading the same packaged raw resource and override `createCapability(context, capabilityId)`. Return the matching AIDL Stub as an `IBinder`, or `null` for an unsupported capability. The SDK handles the protocol-v4 root handshake, sessions, instance creation, configuration normalization, and revocation. A subclass must call `context.enforceCallingCore()` at the start of **every** capability Binder method, including read methods. Use `context.normalizedConfigurationJson` as the frozen settings for that instance, and `context.instanceId` for instance-owned state. Acquiring an endpoint for updated configuration revokes the old instance context and creates an endpoint with the new snapshot. The [example service](../examples/standalone-sort-addon/app/src/main/java/dev/modular/examples/standalonesort/StandaloneSortService.kt) demonstrates this flow.

Core calls `getInstanceCapabilityBinder(sessionId, instanceId, configurationJson, capabilityId)` for protocol v4. The earlier root transactions 1–8 remain unchanged; v4 adds `getDescriptorJson()` at transaction 9 and the per-instance method at transaction 10. Protocol v1 is discovery-only, v2 adds sessions, and v3 adds requested Core capabilities. An older module may still serve its existing capability with empty `{}` configuration, but Core rejects nonempty instance configuration for it rather than silently ignoring it. Capability IDs carry a major version, such as `modular.apps.sort/v1`; a breaking sort API requires a new capability ID.

`modular.apps.sort/v1` receives at most 2,000 app keys and labels plus a locale tag. Return a status and an exact permutation of input keys. Core validates the response and retains the current Core app objects. The sort contract supplies no app catalog or launch authority by itself. If your add-on requests a Core host capability, declare it in `requestedCoreCapabilities`; the request only makes it eligible for an explicit package-and-signer-bound grant. `ModuleInstanceContext.appCatalog()`, `appLaunch()`, and `hostCapability()` are convenience accessors, and Core checks the live grant on every host Binder call.

`modular.apps.featured/v1` is a separate optional capability for a small starred
Home section. It receives at most 2,000 current app keys and labels and returns
up to 50 saved pins plus 20 suggestions, with a typed ready, permission-required,
or no-history state. Core renders the section and generic pin picker/reorder
controls; the provider stores pins and implements authenticated `setPinned` and
`replacePinOrder` mutations with a persistent feature revision. The SDK's
`FeaturedAppsEndpoint` enforces caller authentication and structural validation;
the provider must still enforce revision conflicts and commit before acknowledging
success. A pin may remain saved after its app leaves the catalog, while a new pin
must use a key from the latest read. The full contract and compatibility behavior
are in [featured-apps-v1](architecture/featured-apps-v1.md). The existing sort
parcel layout and protocol v4 methods are unchanged.

## Authenticate Core callers

The SDK's default `TrustedCorePolicy.officialSameSigner()` is for official add-ons signed with Core's signer. An independently signed add-on must override `trustedCorePolicy` with `TrustedCorePolicy.pinned(mapOf(corePackageName to setOf(certificateSha256)))`. Pin the exact Core package ID and SHA-256 of its installed **APK signing certificate**. The [published fingerprints](signing-certificates.json) are for the owner's persistent debug and release APKs; disposable CI debug APKs have different certificates. Replace the pins when targeting another Core build. The policy checks both the calling UID's package ownership and the current certificate; the SDK rechecks it on each privileged Binder call. A certificate digest is public identity data, not a private signing key.

Core independently verifies the add-on's current package and certificate for grants. Discovery metadata, an advertised capability, an installed APK, and a Core host Binder reference do not grant authority. Avoid accepting arbitrary package names or trusting an intent action alone as proof of Core identity.

## Install and verify

Build the example with `gradle -p examples/standalone-sort-addon -PmodularSdkRepository=/absolute/path/to/sdk-repository :app:assembleDebug`. Install the add-on APK as a separate package. In Core Settings, inspect and enable its service, create two instances of its provider with `{"reverse":false}` and `{"reverse":true}`, and bind each to `modular.apps.sort/v1` in separate contexts. Switching contexts should change order while Core Home remains usable if the add-on is disabled or fails. Only one context is active at a time.

The APK install is handled by Android; Core does not silently sideload packages. The baseline Home and Settings work with no add-ons. External Home/Drawer rendering, external themes, automation, scripting, MCP transport, and terminal capabilities are later contracts.
