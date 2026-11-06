# Add-on protocol v4

Protocol v4 extends the existing Binder ABI. Transactions 1–8 are unchanged. A v4 service also implements `getDescriptorJson()` (9) and `getInstanceCapabilityBinder(sessionId, instanceId, configurationJson, capabilityId)` (10). `getCapabilityBinder()` (7) remains the compatibility path for earlier providers. Core negotiates using `getProtocolVersion()` before calling v4 methods.

The service declares manifest metadata under `io.github.alexanderwilhelmsenberg.modular.MODULE_DESCRIPTOR` using `android:resource="@raw/module_descriptor"`. The referenced resource is a UTF-8 JSON descriptor, at most 32,768 bytes. The runtime `getDescriptorJson()` result must describe the same provider. Core checks both claims against the legacy handshake fields, the installed package, and its own supported protocol version. A declaration grants no access to Core capabilities; existing package/signing identity checks and user grants still apply. An optional `managementActivity` must resolve inside the provider package.

The descriptor schema is exactly version 1:

```json
{
  "schemaVersion": 1,
  "moduleId": "dev.example.sort",
  "moduleVersion": "1.0.0",
  "displayName": "Example sorter",
  "description": "Orders apps by a chosen direction.",
  "coreProtocol": { "min": 4, "max": 4 },
  "providedCapabilities": ["modular.apps.sort/v1"],
  "requestedCoreCapabilities": [],
  "managementActivity": ".SettingsActivity",
  "instanceConfiguration": {
    "schemaVersion": 1,
    "fields": [
      {
        "key": "direction",
        "title": "Direction",
        "type": "string",
        "default": "ascending",
        "enum": ["ascending", "descending"]
      }
    ]
  }
}
```

`managementActivity` is optional. A provider with no settings uses `"instanceConfiguration":{"schemaVersion":1,"fields":[]}` and receives `{}`. Unknown descriptor properties and schema versions are rejected. Module IDs are lowercase dot-separated names; capability IDs follow the existing `CapabilityId` wire format. The descriptor has at most 32 configuration fields, 32 entries per enum, and 32 declared capabilities per direction. Text and numeric bounds are enforced by `ModuleDescriptorCodec`.

Configuration is one flat JSON object, at most 8,192 UTF-8 bytes before and after normalization. Field keys are unique. Every field has a `title`, a `type` (`boolean`, `string`, or `integer`), and a typed `default`. String fields may declare `minLength`/`maxLength` (0–256); integer fields may declare `min`/`max` (within ±1,000,000,000). String or integer fields may declare `enum`. Unknown keys, wrong types, out-of-range values, nested objects or arrays, and malformed JSON are rejected. Missing keys receive defaults. `ModuleDescriptorCodec.normalizeConfiguration()` returns deterministic JSON with keys sorted. Validation errors use fixed messages and never include the submitted JSON.

Core owns each instance ID and stores its normalized configuration independently. Core supplies the session ID, instance ID, normalized configuration, and advertised capability ID when acquiring an instance Binder. The module should bind that endpoint to those values for its lifetime. Core discards endpoints after session closure, provider disablement, configuration replacement, or Binder death. It obtains a new endpoint when the instance configuration changes. Modules must not treat configuration as authority to access Core data or services.

Protocol v1–v3 providers remain discoverable through their original transactions. They have no v4 descriptor or instance configuration schema; only the empty `{}` configuration is meaningful for them. Core must reject or mark unsupported any non-empty instance configuration rather than silently lose it.
