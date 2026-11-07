# Built-in Most Used

Most Used is an optional **Core** Home feature, enabled with its built-in toggle.
It adds a `★` section before the complete alphabetical app list. Saved pins
appear in manual order before usage-ranked suggestions. An app remains in its
alphabetical section as well. See [ADR-0006](../decisions/ADR-0006-core-most-used-and-feature-order.md)
for the ownership decision and implementation status.

In Core Settings, enable **Most Used**, set **Recent days** (1–30, default 7)
and **Suggested apps** (0–20, default 8), and manage up to 50 pins. The feature
is off by default. Pinning and reordering work without Usage Access. Suggestions
use locally reported foreground time only after the user explicitly grants
Android Usage Access to **Modular Core**. If access is denied or revoked, pins
remain available. If access is granted but there is no foreground history, the
section shows pins and a no-history state. Apps with zero reported foreground
time are not suggested.

Usage Access is an Android special access setting, separate from ordinary
runtime permissions. Core offers an explicit action to open that setting; it
cannot grant access for itself. A grant previously made to the Most Used add-on
does not transfer to Core. Android and device policy control any restricted
setting prompts; the app cannot bypass them. Disabling the Core toggle removes
the star destination while preserving the alphabetical catalog and saved pins.

## Legacy/reference add-on

The separate `addon-most-used` APK remains in source for compatibility and SDK
testing. It uses its own package ID and Android Usage Access grant. An already
selected compatible instance may be imported through validated, authenticated
IPC into Core's store, once and idempotently. Core cannot read an uninstalled
APK or its private files. Installing the reference APK is not required for
ordinary Most Used use. The Signed APKs workflow builds it only when
`include_reference_addons` is selected.

For an older Core that still uses the separate add-on, install a matching debug
or release variant, open **Settings > Add-on modules > Most Used > Settings**,
set the day/count values, and choose **Use on Home**. Its Usage Access must be
granted to the add-on package. Android 17 / One UI 9 restricted-settings behavior
has varied by device; the previous observed path was to acknowledge the blocked
permission row, reopen App info, and use **Allow restricted settings** if the
menu appears. This is historical troubleshooting, not a guaranteed remedy.
Installation warnings remain a separate issue; see
[Android signing](../android-signing.md#installation-warnings-and-restricted-settings).
