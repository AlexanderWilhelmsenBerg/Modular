# Core Home validation

Run these checks on a device in addition to CI. A checked-in checklist is not evidence that the checks passed.

1. Install only the Core debug APK. Verify rows show app-name text only, without icons or profile subtitles. Compare the list with the device launcher, including ordinary third-party apps. With network disabled, open Modular and verify the alphabetical app list appears without module or context setup.
2. Tap an app and confirm it launches. Return and confirm the previous scroll position is retained.
3. Open Settings, press Back, and verify Home returns. Repeat with Android Home after setting Modular as default. Repeated Home presses must not open diagnostics or exit the launcher.
4. Rotate on Home and Settings; recreate the activity. Verify appropriate state restoration and that a fresh HOME intent overrides the Settings destination.
5. Use duplicate app labels, accented labels, and a second device locale. Verify deterministic locale-aware ordering without losing distinct activities.
6. Install, rename/update, suspend, and remove an app. Verify catalog refresh and that unavailable apps cannot be launched.
7. With a managed profile, verify launches target that profile without adding default profile subtitles. Pause the profile and check unavailable feedback. Repeat after removing the profile.
8. Enable TalkBack and large text. Verify row names, profile/state descriptions, touch targets, Settings and Make default controls, and system-bar insets.
9. Remove or stop optional modules. Confirm built-in Home and app launching remain usable.
10. Review the CI unit, lint, and instrumentation reports before distributing the APK. CI on an emulator does not replace managed-profile and accessibility device checks.

CI installs `test-launcher-app` only as an independent discovery fixture. It is not a Core dependency and must not be distributed as a required add-on.
