# ADR-0007 — Local launch learning is the default Most Used source

Status: **Accepted product direction; implementation and acceptance tracked separately**  
Date: 2026-11-07

## Context

ADR-0006 moved Most Used into Core, but its initial ranking depended on Android
Usage Access. Android may restrict that special access for sideloaded APKs, and a
launcher can learn useful suggestions from launches the user makes through its
own Home screen. The Most Used feature itself remains optional and off by default.

## Decision

When Most Used is enabled, **Learn from Modular** is the default suggestion source,
including on upgrades where no source setting has been stored. Core learns from
successful, explicit app launches through its Home list, search results, or star
section. It records private daily counts by exact package/activity/profile app
key, capped at 1,200 daily app buckets. Counts older than 30 days are ignored
when scoring and removed on the next recorded launch or confirmed reset; an
inactive store can still contain older counts until then. The history file is
excluded from Android cloud backup and device transfer. Failed launches and
mere app discovery, display, scrolling, or search do not add usage. Within the
chosen 1–30 day history window (default 7), unpinned suggestions rank by recency-weighted
launch frequency (seven-day half-life) with deterministic ties. Saved pins remain
first in manual order; the suggestion limit remains 0–20 (default 8).

**Android usage history** remains an optional source. Selecting it explicitly
uses Android's `PACKAGE_USAGE_STATS` special access and ranks eligible apps by
system-reported foreground time within the selected window. Core offers the
system settings route only for this source. If access is unavailable, denied, or
revoked, Most Used uses local launch learning while keeping the selected source
choice so Android ranking can resume when access returns. Local learning continues
while Most Used is enabled, regardless of the selected suggestion source. Core
never silently grants or bypasses restricted settings.

The user can confirm **Reset learned usage** to clear only Core's local launch
aggregate. Reset preserves pins, Most Used settings, the selected source, and
Android's system usage history. Turning Most Used off hides the star section and
stops new local learning; saved state remains available if the feature is enabled
again. The complete alphabetical catalog remains available in every state.

## Consequences

Local suggestions require no Android special access, account, network, or
second APK. An app launched outside Modular can contribute to suggestions only
when Android usage history is selected and its permission is available. The
Android manifest declaration remains for that explicit option. Android and
device policy control restricted settings; APK signing or a distribution channel
does not guarantee a particular permission or Play Protect outcome.

This refines [ADR-0006](ADR-0006-core-most-used-and-feature-order.md)'s usage
source and permission behavior. Its Core ownership, default-off toggle, pins,
legacy migration, and requirement to specify basic categories only after Most
Used implementation remain in force.
