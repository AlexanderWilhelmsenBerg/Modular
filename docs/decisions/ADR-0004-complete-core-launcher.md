# ADR-0004 — Complete Core Launcher and Optional Add-ons

Status: **Accepted product direction; implementation pending**
Date: 2026-11-04

## Context

The original specification allowed Core to open a settings/recovery shell until external Home, Drawer, and Sort modules were installed. The product owner clarified that Core must be a finished launcher and add-ons must handle optional helper methods and functions.

## Decision

Ship a complete baseline in the Core APK: a vertical alphabetical app list as Home, app icons and profile identification, exact activity/profile launching, Android HOME integration, secondary Settings, accessibility, and recovery.

Core must work immediately offline without extra APKs, accounts, optional permissions, or context configuration. No separate drawer screen is required. Built-in alphabetical ordering uses displayed labels and device locale with deterministic identity tie-breakers.

Keep baseline components internally separated and use compatible capability abstractions where useful. Internal modularity does not require cross-process IPC. External helpers remain optional APKs using versioned Binder/AIDL and live identity/grant enforcement under ADR-0002.

Contexts and alternative surfaces are optional advanced configuration. Missing or failed helpers restore built-in behavior; failed activation preserves the previous usable configuration. The built-in baseline cannot be removed through add-on management.

Automation, scripting, terminal access, MCP, weather, and richer presentation remain optional. Python remains the preferred first scripting runtime. External surface protocol work does not gate Core Home delivery.

## Consequences

This supersedes the original requirements that all visible features live outside Core, that Core cannot implement alphabetical sorting or production Home, and that a blank/settings-only fresh installation is acceptable.

The existing external Alphabetical Sort module remains useful for contract verification but is not an installation dependency. Minimal Home and Basic Drawer external modules become optional replacement examples.

Security isolation for external APKs remains unchanged. User-facing recovery returns to usable Home rather than a diagnostics-only screen.

## Verification

The product specification defines baseline and failure acceptance tests. Deliver Core usability first, then external helper proof and richer composition. Documentation records target behavior, not a claim that code already implements it.
