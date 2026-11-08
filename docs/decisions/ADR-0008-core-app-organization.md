# ADR-0008 — Core categories, popup folders, and Mega Sorter

Status: **Proposed specification; requested feature scope confirmed, detailed defaults under review**  
Date: 2026-11-08

## Context

[ADR-0006](ADR-0006-core-most-used-and-feature-order.md) deferred category
specification until Core Most Used was implemented. Core local Most Used is now
merged under [ADR-0007](ADR-0007-local-most-used-learning.md). The next user
request covers category pages, popup folders and rapid manual category sorting.

## Proposed decision

Core owns all three features. Categories are optional, individually enabled
pages with a configurable physical order around Home and per-category Show on
Home control. Default names are Games, Media, Social, Productivity, Work and
Finance; custom categories are supported. An app can be in multiple categories,
and Home search still finds non-hidden apps excluded from direct Home rows.

Automatic matching uses local Android category metadata when available, with
explicit per-app/category Include/Exclude overrides. Android supports the Games,
Media, Social and Productivity mapping; Work, Finance and unknown apps remain
manual in the first version. No new restricted permission, online classification
service or installed-package upload is required.

Folders are independent ordered app collections, opened as popups from the
app-list Folders section or Most Used pins. Folder membership does not remove
the app from its normal list. They are not Android directories, add-on instances,
categories themselves, or usage-ranked suggestions.

The expanded request adds per-folder smart visibility for headset connection,
time schedules, connected Wi-Fi and map-defined areas. Both Any/All matching are
supported, with Any confirmed as the default. Every folder remains configurable
in Settings while hidden. Rules change shortcut eligibility, not membership.
The proposed low-resource implementation evaluates while the launcher is visible,
uses shared callbacks and bounded location refresh, and defers background
geofencing. Optional Wi-Fi/location rules require relevant permissions; they do
not require Usage Access. A compatible map renderer/tile provider remains to be
selected. Online app metadata services are researched options, not selected
dependencies; local classification continues to work without them.

Mega Sorter adds manual category memberships through directional row swipes.
Per-row fixed-slot animation must not block another row or vertical scrolling.
Mode gestures take precedence over ordinary horizontal page changes. Accepted
assignments persist independently of animation and support precise Undo.

The detailed [product draft](../product/categories-folders-mega-sorter.md)
distinguishes user-confirmed behavior from proposed defaults, including normal
pager direction, Home/pin visibility precedence, folder placement and animation
timing. It contains the acceptance contract and references.

## Consequences and compatibility

This proposes a limited refinement of older complete-main-list requirements:
the raw catalog and global search remain complete, while an explicit category
setting may suppress direct Home app entries. Existing explicit Hide continues
to take priority across all browsing surfaces.

Implement a separate organization model and shared projections rather than
repurposing module contexts or removing catalog entries. Typed app/folder pin
storage must migrate existing exact app identities and order without loss.
The module API and external-featured contracts remain unchanged in this scope.

This decision does not implement the features, certify physical-device
behavior, alter signing, publish to an app store, or submit a Google appeal.
See the [implementation plan](../implementation/categories-folders-mega-sorter-plan.md).
