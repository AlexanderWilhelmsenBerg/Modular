# Featured apps capability v1

This remains a generic, versioned add-on contract. Core's built-in Most Used
now owns the ordinary starred feature; see [ADR-0006](../decisions/ADR-0006-core-most-used-and-feature-order.md).
Reference and third-party providers can still use this contract.

`modular.apps.featured/v1` contributes a small, ordered section before Core's
alphabetical catalog. It is separate from `modular.apps.sort/v1`; neither that
capability's parcel layout nor the protocol-v4 root service changes. A provider
advertises the new ID in its descriptor and returns an
`IFeaturedAppsCapability` Binder for a Core-owned instance through
`getInstanceCapabilityBinder`. The Binder is valid only for that session,
instance, and normalized configuration. Providers authenticate every call with
`ModuleInstanceContext.enforceCallingCore()`. The SDK's optional
`FeaturedAppsEndpoint` supplies that check and structural validation.

## Wire calls

| Transaction | Request | Response |
| --- | --- | --- |
| `getFeaturedApps` | `FeaturedAppsRequestParcel(requestId, catalogRevision, localeTag, items)` | `FeaturedAppsResultParcel(requestId, catalogRevision, featureRevision, status, featureState, pinnedKeys, suggestedKeys, message)` |
| `setPinned` | `FeaturedPinRequestParcel(requestId, catalogRevision, expectedFeatureRevision, key, pinned)` | `FeaturedMutationResultParcel(requestId, status, featureRevision, message)` |
| `replacePinOrder` | `FeaturedPinOrderRequestParcel(requestId, expectedFeatureRevision, orderedKeys)` | `FeaturedMutationResultParcel(requestId, status, featureRevision, message)` |

The read request sends at most 2,000 current `AppSortItemParcel` values, with
exact package/activity/profile keys and labels, plus a locale tag. Core sends
the **complete** catalog when it fits. If the catalog exceeds the IPC bound,
Core keeps displaying all apps alphabetically and omits the featured
contribution until a future capability supports paging. It must never truncate
the visible catalog. The request ID distinguishes overlapping reads, while the
catalog revision distinguishes catalog snapshots. Both are echoed in the
response. Instance and configuration identity come from the acquired v4 Binder,
not from caller-controlled parcel fields.

The successful result contains up to 50 saved pins in manual order and up to 20
unpinned suggestions in rank order. `featureState` is `STATE_READY` (0),
`STATE_PERMISSION_REQUIRED` (1), or `STATE_NO_HISTORY` (2). Pins remain valid
without Usage Access. A saved pin absent from the current catalog may be
returned for management and removal, but Core never makes it an actionable Home
row. Every suggestion must occur in the current catalog. Keys must be unique
within and across both lists. `status` is `STATUS_SUCCESS` (0),
`STATUS_INVALID_ARGUMENT` (1), or `STATUS_FAILED` (2). Failed results have
empty key lists. Core validates all of these constraints before displaying a
starred section, and rejects responses from an old session, instance,
configuration, request, or catalog generation.

`setPinned` sets the requested state; it is never a toggle. Adding requires a
key in the latest validated read catalog for the same instance and revision.
Removing a saved pin works even if its app has disappeared from the catalog.
`replacePinOrder` contains a unique permutation of **all** currently saved
pins, including unavailable ones. Mutations use an expected feature revision
to detect concurrent edits. Their status is `STATUS_SUCCESS` (0),
`STATUS_INVALID_ARGUMENT` (1), `STATUS_CONFLICT` (2), or `STATUS_FAILED` (3).
The response echoes its request ID and returns the current or committed feature
revision. A provider acknowledges success only after durable persistence;
failure leaves the previous saved state intact. Retrying a set-state operation
whose desired state is already committed should succeed. Core rereads after a
successful mutation and after returning from provider settings.

All typed lists reject an oversized count **before** allocating their elements.
Shared `FeaturedAppsContract` checks bounded string lengths, exact echoes,
status values, unique keys, catalog membership, and pin-order shape. Individual
key and label strings are still decoded by Android's Parcel implementation
before their length can be checked, so Binder transaction limits remain the
outer byte bound. Providers should return short, privacy-safe failure messages
and never log pin lists or usage history.
