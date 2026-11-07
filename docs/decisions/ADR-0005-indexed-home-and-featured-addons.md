# ADR-0005 — Indexed Core Home and add-on-owned featured apps

Status: **Accepted product direction; implementation planned**
Date: 2026-11-07

Ownership update: [ADR-0006](ADR-0006-core-most-used-and-feature-order.md)
supersedes this ADR's add-on-owned Most Used setup, ranking, and pins. Its generic
featured-apps contract and Core Home presentation decisions remain applicable.

## Context

The product owner supplied Niagara reference screenshots and requested icons,
optional wallpaper, letter sections, animated alphabet navigation, bottom
controls, and pull-down reachability. Most Used should add a star destination
and pinned apps. This revises the earlier text-only Core presentation decision.

The existing sort contract returns one permutation of the entire catalog. It
cannot describe a starred subset plus an independently alphabetical catalog, or
authenticated persistent pin actions. Adding fields to that parcel would risk
breaking existing APKs.

## Decision

Core owns icons, wallpaper presentation, alphabetical sections/navigation,
bottom search/settings, and reachability. These must work without an add-on.
Most Used owns ranking and persistent pins. A new, bounded featured-apps
capability supplies keys and state to Core's generic rendering and pin controls.

Keep the full alphabetical catalog below the featured section. An app may appear
once in the featured section and once in its alphabetical section; search
deduplicates by exact app key. Pinning does not require Usage Access. Usage
ranking does, and remains entirely outside Core.

Preserve v1 sort ABI and protocol compatibility. Negotiate the new capability
explicitly and migrate a compatible selected Most Used instance atomically.
Legacy non-alphabetical sort mode remains flat without misleading alphabet UI.
No arbitrary external Compose code or general external surface engine is needed.

## Consequences

This supersedes only the text-only/add-on-only icons and alphabet-index boundary
in ADR-0004. It preserves a complete offline Core, exact identity/profile launch,
optional independent APKs, live caller authorization, and recovery guarantees.
Profile subtitles and icon packs are not made baseline Core features.

The [v2 specification](../product/home-presentation-v2.md) defines defaults,
interaction semantics, migration and acceptance. The
[implementation plan](../implementation/home-presentation-v2-plan.md) sequences
the work. Neither document claims this target is already implemented.
