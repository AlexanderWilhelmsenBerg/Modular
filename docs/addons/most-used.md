# Built-in Most Used

Most Used is an optional **Core** Home feature, enabled with its built-in toggle.
It adds a `★` section before the complete alphabetical app list. Saved pins
appear in manual order before usage-ranked suggestions. An app remains in its
alphabetical section as well. See [ADR-0006](../decisions/ADR-0006-core-most-used-and-feature-order.md)
for Core ownership and [ADR-0007](../decisions/ADR-0007-local-most-used-learning.md)
for the local learning default.

In Core Settings, enable **Most Used**, open **Configure Most Used**, set
**History** (1–30 days, default 7) and **Suggestions** (0–20 apps, default 8),
and manage up to 50 pins. The feature is off by default. Its default source is
**Learn from Modular**, also on upgrades with no saved source choice. Open apps from
Modular's Home list, search results, or star section to teach it your preferences.
Only successful, explicit launches count. Suggestions use recency-weighted launch
frequency within the selected history window. Core stores private daily counts
by exact app and profile identity, capped at 1,200 daily app buckets. Counts
older than 30 days cannot affect suggestions; Core removes them on the next
recorded launch or when learned usage is reset. The local history file is
excluded from Android cloud backup and device transfer. Pins and local
suggestions need no special permission, account, network, or separate APK.
When there is no eligible history, pins remain visible and the section shows its
no-history state. Set Suggestions to zero to show pins alone.

To rank apps by system-reported foreground time, explicitly select **Android
usage history** as the source and grant Android Usage Access to **Modular Core**.
This can include use outside Modular. Usage Access is a special access setting,
separate from ordinary runtime permissions. Core offers an action to open the
system setting but cannot grant access for itself. If the grant is unavailable,
denied, or revoked, suggestions fall back to local launches while pins remain
visible; the Android source choice remains saved. Core continues learning
successful launches while Most Used is enabled, whichever source is selected.
A grant previously made to the Most Used add-on does not transfer to Core.

Android and device policy control restricted setting prompts; Core cannot
bypass them. See [Android's restricted settings help](https://support.google.com/android/answer/12623953)
for Google's device guidance, including manually allowing restricted settings
where the system offers that option. Availability and labels vary by device.
Disabling Most Used removes the star destination and stops new local learning,
while preserving the alphabetical catalog and saved state. **Reset learned
usage** asks for confirmation and clears only Core's local launch aggregate; it
keeps pins, history and suggestion settings, the source choice, and Android's
system usage history.

## Quick check

1. Enable Most Used and keep **Learn from Modular** selected. Open a few apps from
   Home or search, return to Home after each launch, and check that unpinned
   suggestions appear under the star. Pin an app and check that it stays first.
2. Change the history window, then confirm **Reset learned usage**. Local
   suggestions should clear; pins and settings should remain. Open an app again
   to begin learning anew.
3. Optionally select **Android usage history**, open its Usage Access setting,
   and grant access to Core. Check that foreground use affects suggestions.
   Revoke access to check the local fallback. This step is unnecessary for the
   default local source.

## Legacy/reference add-on

The separate `addon-most-used` APK remains in source for compatibility and SDK
testing. It uses its own package ID and Android Usage Access grant. An already
selected compatible instance may be imported through validated, authenticated
IPC into Core's store, once and idempotently. Import preserves Core's toggle;
enable Most Used in Core Settings to show the imported pins. Keep the old add-on
installed until the import completes. Core cannot read an uninstalled
APK or its private files. Installing the reference APK is not required for
ordinary Most Used use. The Signed APKs workflow builds it only when
`include_reference_addons` is selected.

For an older Core that still uses the separate add-on, install a matching debug
or release variant, open **Settings > Add-on modules > Most Used > Settings**,
set the day/count values, and choose **Use on Home**. Its Usage Access must be
granted to the add-on package. Android 17 / One UI 9 restricted-settings behavior
has varied by device; consult the linked Android help and the device's own
prompts for this legacy add-on.
Installation warnings remain a separate issue; see
[Android signing](../android-signing.md#installation-warnings-and-restricted-settings).
