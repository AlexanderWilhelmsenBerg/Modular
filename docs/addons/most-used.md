# Most Used add-on

Most Used adds a **★ starred section** above Modular's complete alphabetical app list. Saved pins appear first in the order you choose. Then up to eight unpinned apps appear by Android-reported foreground time. Each app remains in its usual alphabetical section as well.

## Set up Most Used

Install the Most Used APK alongside Modular Core. Use matching variants: **Most Used Debug** with **Modular Debug**, or **Most Used Release** with **Modular Release**. Installing the add-on alone does not change Home.

1. Open **Modular Core > Settings > Add-on modules**. Enable Most Used if needed, then tap **Settings** on its card.
2. Set **Recent days** (1–30, default 7) and **Suggested apps** (0–20, default 8), then tap **Use on Home**. The star appears on Home, even before granting Usage Access.
3. Long-press an app on Home and choose **Pin to starred apps**. The module Settings app picker and **Pinned apps** list also let you add, reorder, or remove pins. Pinned apps stay in the order you set and do not count toward the suggestion limit.
4. To add usage suggestions, open **Permissions and settings** on the Most Used card. On the add-on screen, tap **Open Usage Access settings**, select Most Used in Android settings, and allow usage access. Return to the add-on screen to check its state. Android grants this access to the Most Used APK; Modular Core does not request it.

When Usage Access is off or revoked, saved pins still appear and the starred section offers the permission action. When access is on but Android reports no foreground history for the selected period, the section shows a no-history message and saved pins. An empty section still offers Pin apps. Apps with zero reported foreground time are not suggested.

The add-on stores ordered pins privately per configured module instance, using the exact package, activity, and profile identity. A pin for an uninstalled app is retained and shown as unavailable in module Settings so it can be removed or restored by reinstalling the same app. Paused or unavailable apps do not become launchable Home rows. Usage history is queried locally, not stored or uploaded.

Use **Use alphabetical order** or select another module to remove the star from Home. The add-on still offers its original whole-list sort capability to older Core versions. A current Core selects the starred capability when available; the legacy mode requires Usage Access and otherwise falls back to alphabetical order.
