# Module management

This change completes the Settings management surface around the existing registry,
composition, and security stores. It extends the text-only Core Home work; it does
not change Home rows. The subsequent [Core completion](core-completion.md) adds the agreed search/theme controls and optional Home sort integration.

## Behavior

- Module enablement and the last 200 lifecycle events persist locally. Events carry
  time, service/package, stage, and outcome; users can filter, copy, or clear them.
- Settings opens inspection connections for enabled modules. Home retains only
  providers selected by the active saved context. With no context, Home requires
  no module connections. Discovery and an enabled preference do not grant Core
  capabilities.
- Retry replaces a failed connection. Binding/handshake deadlines, stale result
  rejection, and immediate host revocation protect against slow or dead providers.
  A synchronous Binder call cannot be forcibly cancelled; an outstanding call
  prevents overlapping retries for that service until it returns.
- Instance and context editors use the existing validated stores. Failed saves
  remain in the editor, referenced-instance deletion is rejected, and failed
  activation preserves the prior context. JSON configuration is an advanced
  interim editor until module-provided configuration schemas are implemented.
- Permission controls use the installed package signing identity and the existing
  live authorization checks. Multiple services in one package share grants.
- Uninstall opens Android's confirmation for the whole APK. Core refreshes after
  returning and retains saved configurations even when the package is removed.

## Specification review and remaining work

| Area | Evidence / remaining work |
| --- | --- |
| Baseline launcher | Text-only alphabetical Home, launcher visibility and warm Home navigation have existing automated coverage; user confirmed complete catalog and simple rows. |
| Management | Enable/disable, retry, diagnostics, uninstall handoff, instance/context editors and grants are implemented in this change; validation results belong in the PR checks. |
| Composition runtime | Storage, validation and route selection exist. Selected optional sorting now applies to Home with timeout, invalid-result validation and stale-response rejection; richer filtering remains later work. |
| External presentation | A bounded future theme contract is documented; external surface/configuration schemas and external theme loading remain later work. |
| Home controls and themes | Search, gear, removal of Apps heading, and persisted Light/Dark/AMOLED are implemented by Core completion. |
| Device acceptance | Physical-device uninstall cancellation/success, paused work profiles, TalkBack, large font, process restoration and OEM-specific service behavior still require device validation. |

The current Core implementation covers the agreed baseline and management scope.
External rendering and other explicitly later add-ons remain deferred; physical-device
acceptance is tracked separately from automated CI evidence.

## Design review

Hallmark review applied to native Compose Settings: existing Material typography,
color tokens and platform controls are retained. Add-on actions are primary;
module details, event history and technical counters are expandable. Destructive
actions distinguish saved configuration from Android package removal. Labels and
controls must remain usable at compact widths and with font scaling.

Hallmark source-review scores (1–5): philosophy 4, hierarchy 4, execution 3,
specificity 4, restraint 4, variety 3. These are a source review, not a claim of
visual verification on physical devices.

## Manual acceptance

1. Open Home with no context. Verify app launching and Settings remain usable
   with zero add-ons and after an add-on fails.
2. In Settings, disable an add-on, recreate Core and verify it stays disabled.
   Enable it and inspect its status. Retry a failed service and inspect the reason.
3. Inspect the module event log, filter errors, copy the log, restart Core and
   verify retained history; clear it and verify clearing survives restart.
4. Create an instance with valid JSON; reject invalid JSON without losing the
   edit. Bind it in a context; reject deletion while referenced. Deactivate before
   changing active bindings. Remove the binding and then delete the instance.
5. Revoke a requested Core permission and verify existing sessions lose access.
6. Cancel Android uninstall and verify the add-on remains. Confirm uninstall and
   verify discovery removes its services while unresolved saved references remain
   visible. Return Home and launch an ordinary app.
