# ADR-0006 — Core owns Most Used; categories follow

Status: **Accepted product direction; implementation and acceptance tracked separately**  
Date: 2026-11-07

[ADR-0007](ADR-0007-local-most-used-learning.md) refines this decision: local
launch learning is the default suggestion source, and Android Usage Access is
required only when the Android foreground-time source is explicitly selected.

## Context

Most Used's starred section is an everyday launcher function. Requiring a second
APK for pins and usage suggestions makes the default launcher harder to use and
puts Android's Usage Access setting on a different package from the launcher.
Basic app categories are another everyday function, but their specification
starts **after** the Core Most Used implementation; this decision neither designs
nor implements categories.

## Decision

Core owns a built-in **Most Used** toggle, off by default, its ordered pins,
usage-ranked suggestions, and the optional Android `PACKAGE_USAGE_STATS` special
access. Pins work without Usage Access. When enabled, the star destination shows
pins before suggestions and retains the complete alphabetical catalog. Keep the
existing limits: 50 pins, a 1–30 day window (default 7), and 0–20 suggestions
(default 8). The user explicitly opens Android's Usage Access settings for the
Core package; grants to the old add-on cannot transfer to Core.

Core stores its own pin and setting state. On upgrade, automatically import a
currently selected official legacy Most Used instance's validated pins and
settings over authenticated IPC, once and idempotently. Persist the import
before detaching the old binding; retry after errors. Import never changes Core's
toggle: enabling Most Used is an explicit choice in Settings. Preserve existing
Core pins and each explicitly changed Core setting. Imported settings are available
even if the user switches contexts during migration; a stale binding is not detached.
Core does not read another APK's private files, and an uninstalled add-on cannot
be queried for migration. Exact
package/activity/profile keys remain distinct; missing pins are non-actionable
until the matching app returns.

The older Most Used add-on and reference sorting APK remain in source for
compatibility and SDK tests. They are no longer the ordinary way to enable Most
Used. Everyday launcher functions belong in Core; complex integrations and
alternative behaviors use the add-on framework. Specify basic categories only
after this implementation is finished.

## Compatibility and consequences

This supersedes the **Most Used ownership and setup** decisions in
[ADR-0005](ADR-0005-indexed-home-and-featured-addons.md), its Home v2 plan,
and related add-on setup instructions. ADR-0005's Core Home presentation and
the generic `modular.apps.featured/v1` contract remain valid for third-party or
reference providers. The v1 sort ABI and existing package IDs stay intact.

The signed APK distribution builds Core debug and release by default. An
explicit workflow option includes legacy/reference add-ons for SDK testing.
Private-key and certificate policy is unchanged. Neither Play/F-Droid publication
nor resolution of Android installation warnings follows from this decision.
