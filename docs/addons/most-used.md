# Most Used add-on

Most Used puts apps with the most **foreground time** in the selected lookback period first on Modular Home. It measures time spent in apps, rather than launch count. Apps with no recorded time follow in alphabetical order. If Android has no usage history for any listed app, the whole list is alphabetical.

## Set up Most Used

Install the Most Used APK alongside Modular Core. Use matching variants: **Most Used Debug** with **Modular Debug**, or **Most Used Release** with **Modular Release**. If both variants are installed, enable only the one matching Core; both advertise the same provider ID. Installing the add-on alone does not change Home's order.

1. Open **Modular Core > Settings > Add-on modules**. Enable Most Used if needed, then tap **Settings** on its card.
2. Tap **Permissions and settings**. On the add-on screen, tap **Open Usage Access settings**, select Most Used in Android settings, and allow usage access. Return to the add-on screen to check its access state. Android grants this access to the Most Used APK; Modular Core does not request it.
3. Tap **Configure in Modular** to return to its module settings. Set **Recent days** to a number from 1 through 30 (default seven), then tap **Use on Home**. This saves the setting and selects its app order; there is no separate instance or context to create.
4. Return to Home. Apps with more foreground time during the selected period appear earlier. Change **Recent days** in the same module settings page and save to apply a new period.

The add-on reads Android's local usage statistics and returns ordered app keys to Core. It does not need Core's app catalog grant, upload usage data, or store a copy of usage history. To stop using its order, tap **Use alphabetical order** in its settings dialog or choose another sort module. If the add-on is disabled, unavailable, or loses Usage Access, Core falls back to alphabetical order. Android may have little or no history on a fresh device, so alphabetical order is also expected until foreground time is recorded.
