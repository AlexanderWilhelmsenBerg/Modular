<!-- Hallmark · pre-emit critique: P4 H4 E4 S5 R5 V4 -->

# Core Home and Settings design review

Source-only Hallmark audit of the native Compose UI against product spec sections 11, 14, and 15. This review inspected code, not rendered screenshots or a running Android device. Hallmark's web page, CSS, and marketing-layout rules do not apply to this launcher; Material 3 components and platform conventions are the appropriate baseline.

## Findings, ranked

### Minor

- **Every section padded the same (native list variant)** — `ModularApp.kt:86–93` Settings list and `CompositionManagementUi.kt:88–157` configuration cards. The repeated full-width cards and near-identical 12/16 dp spacing make the long administration screen harder to scan. Give the top-level sections clearer grouping and reserve cards for individual entities; keep the native Material vocabulary.

**Count:** 0 critical · 0 major · 1 minor.

## What the source gets right

- Home retains a plain alphabetical app list with name-only rows, consistent with the restrained launcher brief. Rows have at least 48 dp height; disabled rows expose an unavailable state.
- Search and Settings have 48 dp resting height. Search pairs a magnifier with visible text; the gear has an explicit `Settings` accessibility label. Focused search keeps Settings reachable.
- The expanded search has a theme-aware outline, so its boundary remains visible against the true-black AMOLED canvas; its minimum height can grow with larger text.
- Search has a separate no-results message, a labeled clear control, and a Back path that ends editing. The controls use the app's Compose color roles rather than one-off local colors.
- Settings offers Light, Dark, and AMOLED using native radio semantics. Theme storage uses stable IDs and resolves unknown IDs to Light. The AMOLED scheme explicitly makes its main surfaces true black.
- Module and capability switches now use neutral labels, leaving on/off to native switch semantics. The event-log filter controls wrap on compact widths, and a long selected module name ellipsizes within the available width.

## Device verification still required

Run the instrumentation suite and inspect a real device or emulator at compact width, 200% font scale, with TalkBack, keyboard/insets, and each built-in theme. Check text and control contrast, search caret visibility and scrolling, the AMOLED field outline, dialogs, system bars, and Settings card hierarchy. Code inspection cannot establish rendered contrast, clipping, or touch behavior across OEM devices.
