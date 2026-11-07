# Most Used add-on

Most Used puts apps with the most **foreground time** in the selected lookback period first on Modular Home. It measures time spent in apps, not how often you launch them. Apps with no recorded time follow the used apps in alphabetical order. If Android has no usage history for any app in the list, the whole list is alphabetical.

## Install and allow usage access

Install the Most Used APK as a separate Android app alongside Modular Core. Use matching variants: **Most Used Debug** with **Modular Debug**, or **Most Used Release** with **Modular Release**. The signed artifact contains separate APKs for each variant. If both add-on variants are installed, enable only the one matching the Core variant you use; both advertise the same provider ID. Installing an add-on does not grant it usage access or activate its sort order.

Open **Most Used** from your Android app list, then tap **Open Usage Access settings**. Select Most Used and turn on **Permit usage access** (the wording varies by Android version). Return to Most Used to check its access state. Android grants this access to the Most Used APK; Modular Core does not request it. The add-on reads Android's local usage statistics to calculate the order and sends Core only the ordered app keys.

## Activate the sort order in Modular

1. Open **Modular Core > Settings**. Refresh the module registry if Most Used is not listed, and enable the **Most Used** service.
2. Select **Add instance**, choose provider ID `modular.addon.sort.mostused`, give the instance a name, and enter configuration JSON such as `{"days":7}`. Save it. `days` is an integer from 1 through 30; 7 is the default, so `{}` also uses seven days.
3. Select **Add context** and give it a name. Under capability `modular.apps.sort/v1`, select the Most Used instance, then save and **Activate** the context.
4. Return to Home. Apps with more foreground time during the chosen period appear earlier. Changing the number of days requires deactivating the context before editing its instance, then activating it again.

Only one context is active at a time. The selected context's sort binding controls Home. To stop using Most Used, deactivate that context or activate another context. Disabling or uninstalling the add-on, or revoking its Android Usage Access, makes Core use its built-in alphabetical order. Re-enable access and activate the context again to resume the usage-based order.

Most Used reads usage history only on the device when Core asks it to sort. It does not need Core's app catalog grant, does not upload usage data, and does not store a copy of usage history. Android may retain little or no history on a fresh device; in that case alphabetical order is expected until foreground time is recorded.
