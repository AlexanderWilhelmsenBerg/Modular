# Module settings and Home sort activation

This records the legacy/reference add-on UI. [ADR-0006](../decisions/ADR-0006-core-most-used-and-feature-order.md)
supersedes it for ordinary built-in Most Used setup, which now belongs to Core.

The ordinary path is **Core Settings > Add-on modules > [module] > Settings**. The module dialog renders the provider's declared configuration fields as native controls. Most Used declares **Recent days** (1–30, default 7) and **Suggested apps** (0–20, default 8). Users change these values and tap **Use on Home** to save and select its starred section. The save validates the live descriptor, stores the normalized configuration, and updates Home's selected featured binding together. Re-saving a changed value updates the active binding without asking the user to deactivate a context. **Use alphabetical order** removes its Home selection. The existing instance and context editors remain available for advanced composition; ordinary setup requires neither JSON nor manual instance or context creation.

The dialog links to a module's management screen when it declares one through **Permissions and settings**. Most Used's management screen reports whether Android Usage Access is on and offers **Open Usage Access settings**. The system permission is granted to the add-on package. Pins stay visible without it; suggestions require access. Core's generic **Pinned apps** controls provide picker, reorder and remove actions.

The UI uses the app's Material theme, typography, spacing, and native field and switch controls. The primary task is editing settings and applying them to Home. Loading and empty states must explain whether discovery is still running or no modules are installed. A missing route, invalid field, save failure, or disabled service needs a visible message and a useful next action. A permission-off state needs the management link. Screen review should include narrow widths, large font, TalkBack labels, and light and dark themes.

## Acceptance evidence

The cross-APK instrumentation test saves Most Used through `ModuleSettingsController`, checks default and changed `days` values in the active binding, and checks denied and revoked permission fallback. With Usage Access granted, it foregrounds the independent `test-launcher-app` Activity for 2.5 seconds, then polls the add-on through Core until that real package ranks ahead of an unused fixture whose label would otherwise sort first. A wrong-profile serial remains alphabetical. The test also checks successful zero-data ordering with fake package names and preserves the source app objects.

These are automated assertions, not a claim of physical-device or visual review. The user reported blank add-on screens and no visible Usage Access control; those reports motivated the management-screen and link repair. Record CI instrumentation results and any screenshots separately when they are available. A source review can confirm Material theme use and state handling, while actual rendering, accessibility, and OEM permission screens still need device evidence.
