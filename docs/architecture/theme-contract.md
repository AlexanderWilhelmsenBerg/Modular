# Theme extension boundary (design for a later phase)

Core ships and renders Light, Dark, and AMOLED without an add-on. The current appearance preference stores only these stable IDs (`light`, `dark`, `amoled`); there is no external theme loader in this phase. An unknown or unavailable ID resolves to built-in Light so Home and Settings remain usable.

Before external themes become selectable, Core will expose a versioned, data-only theme descriptor. A descriptor contains a schema version, an add-on provider identity, a stable theme ID, a display name, a light/dark system-bar icon mode, and a bounded set of semantic color tokens for the Material color scheme. Colors are opaque ARGB values. Core owns the Compose renderer, typography, layout, controls, navigation, and system-bar application. The descriptor cannot contain executable UI, file paths, network URLs, resource references, or scripts.

Version 1 has these fixed bounds:

- `schemaVersion`: exactly `1`.
- Encoding: a UTF-8 JSON object of at most 8,192 bytes, with no unknown fields.
- `themeId`: 1–64 ASCII letters, digits, dots, underscores or hyphens; unique within the provider.
- `displayName`: 1–80 Unicode code points, excluding control characters.
- `barIcons`: exactly `light` or `dark`; `amoled`: a boolean.
- Provider identity comes from the authenticated module session, never from an untrusted claim in JSON. Core qualifies the ID with that identity.
- At most 32 descriptors per provider. Duplicate object keys and duplicate theme IDs are rejected.
- Required color tokens: `primary`, `onPrimary`, `primaryContainer`, `onPrimaryContainer`, `background`, `onBackground`, `surface`, `onSurface`, `surfaceVariant`, `onSurfaceVariant`, `outline`, `outlineVariant`, `error`, and `onError`.
- Each color is exactly `#FFRRGGBB` (opaque ARGB hexadecimal). Core derives all other roles from the selected built-in base. External descriptors cannot supply typography, dimensions, animation or additional tokens.

 Core must reject missing required tokens, unknown versions, malformed colors, duplicate IDs, excessive size, and low-contrast text or controls. Contrast checks should cover body text against background and surface (at least 4.5:1), and essential controls or their boundaries against adjacent surfaces (at least 3:1). A descriptor claiming AMOLED must use true black for background, surface, and the large surface-container roles, with no tonal surface tint. External descriptors must not change Core's built-in palettes.

When that protocol is implemented, persist a provider-qualified theme ID and a built-in fallback ID together. Resolve and validate an external descriptor before activating it. On provider removal, disablement, incompatibility, invalid data, or rendering failure, use the saved built-in fallback immediately and keep the appearance selector reachable. Failed writes retain the previous persisted choice and show an error. This design does not require external themes, module connections, or any setup for the built-in launcher.
