# Slice 9 — Drawer composition and Core management

Implemented on a follow-on branch based on Slice 8 fix 50ed456d. Exact-HEAD
validation is recorded in PR #9; checkpoint 799e0c9a passed full Android CI.

The separate reference-drawer-basic APK advertises modular.surface.drawer/v1.
Core supplies a bounded app snapshot ordered by the context's external Sort
provider through ISurfaceDrawerCapability. Core checks the Drawer package's
live AppsCatalog grant before transferring app data. The Drawer requests both
catalog and launch capabilities; every launch is checked again by Core.
The module owns profile filtering, 30-item pagination, labels, opaque actions,
and independent per-instance state. Core only validates/renders the existing
Surface Protocol v1 nodes. No app drawer implementation is embedded in Core.

Launcher setup chooses available Home, Drawer and Sort providers and an optional
Android profile. It atomically creates three instances, their context bindings
and activation. Work and Personal can use the same installed modules with
independent instance configurations. Context removal atomically removes its
unused instances and returns to recovery when the active context is removed.

Module enable/disable policy persists separately from grants. Disabling closes
the module session and removes its routes; late handshake callbacks cannot
re-enable a disabled module. Signing-bound grant controls explicitly allow or
revoke requested supported capabilities. Installing a module does not grant
arbitrary Core authority; the reviewed same-signer bootstrap remains unchanged.

Tests cover model paging/profile isolation and stale actions, atomic launcher
setup/removal, and real separate-APK Binder flow through the external Sort and
Drawer modules. The cross-APK test uses a recording Core app repository to
verify launch requests and grant revocation deterministically; it does not
claim that Android work profiles are provisioned in the emulator.

Manage Core also supports atomic removal of one capability from a context,
preserving shared instances and other contexts. An edited active composition is
deactivated until explicitly activated again. The completed local MCP broker and
remaining Core requirement mapping are documented in core-completion.md.
