# Phase 1 — Shared App Repository

Status: **Implemented and CI-verified in PR #2**

## Purpose

Core owns the authoritative launcher view of installed launchable activities and profiles.

Drawer, filter, search, sorting, Home, and future automation modules must not independently query Android packages and invent incompatible views of the device.

## Platform source

The implementation uses Android `LauncherApps`.

For each profile returned by `LauncherApps.getProfiles()`, Core queries:

`LauncherApps.getActivityList(null, user)`

Core registers `LauncherApps.Callback` for package changes.

It also listens for profile add/remove/availability broadcasts so profile changes refresh the catalog.

## Persistent profile identity

The public Core model does not persist `UserHandle`.

Each profile is represented by `UserManager.getSerialNumberForUser(user)`.

Android documents the profile serial number as the persistent identity and recommends converting it back to a `UserHandle` when needed.

## Core model

`AppKey`

- package name
- activity class name
- profile serial

`LaunchableApp`

- AppKey
- user-visible label
- first-install time
- enabled flag
- optional loading progress

`AppProfile`

- profile serial
- quiet-mode state

`AppCatalog`

- generation
- profiles
- launchable activities
- refresh state/error

The model intentionally contains no Android `Drawable`, `UserHandle`, or arbitrary `PackageManager` object.

This makes it suitable for later projection into the public module/capability protocol.

## Ordering

The repository only provides deterministic identity ordering:

1. profile serial
2. package name
3. activity class

It does **not** alphabetically sort by label, frequency, usage, category, or user preference.

Those are module responsibilities.

## Launching

`AppRepository.launch(AppKey)` resolves the profile serial back to a current `UserHandle` and delegates to `LauncherApps.startMainActivity()`.

If the profile no longer exists, the launch fails rather than accidentally launching the same package in another profile.

## Private Space

Android 15+ requires a default launcher to hold `ROLE_HOME` and declare `ACCESS_HIDDEN_PROFILES` before Private Space applications become visible.

That permission is deliberately **not declared in this slice**.

Declaring it creates additional launcher obligations, including a separate Private Space container and lock/unlock/hide/show behavior. Those requirements should be implemented together in a dedicated slice rather than partially claiming support.

The repository therefore supports the profiles currently visible through `LauncherApps` and does not assume every secondary profile is a work profile.

## Recovery UI

Core's recovery/settings screen shows only repository diagnostics:

- launchable activity count
- visible profile count
- refreshing/error state

It does not render the app catalog as a drawer.

## Verification

The application repository compiled successfully as part of the Phase 1 Android application.

CI passed:

- app and module-api unit tests;
- app and module-api Android lint;
- debug APK assembly.
