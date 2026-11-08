<!-- Hallmark · native Compose · studied reference: user-provided Niagara settings screenshots -->
<!-- Hallmark · source critique: P4 H4 E4 S5 R4 V4; native screenshot review required before merge -->

# Settings and launcher identity

## Design intent

Modular settings are for people arranging their launcher. Use a restrained, flat
list with a recognizable brand header, outline icons, aligned titles, short
descriptions, and native switches. The user's Niagara screenshots inform the
hierarchy and spacing. The logo, wording, feature grouping, and vector assets
belong to Modular.

Preserve the existing Material 3 typography, light/dark/AMOLED color roles,
Android system bars, and touch behavior. Use the existing native theme tokens;
web typography, CSS, marketing headers, and footer rules do not apply.

## Requirements

- Settings opens to a landing page titled **Modular settings**. An original
  interlocking two-block M appears above the title.
- Four icon-led entries lead to **Productivity**, **Appearance**, **Add-ons**,
  and **Advanced**. Ordinary launcher choices are separated from diagnostics.
- Productivity retains Core Most Used with its permission-free local learning,
  Categories, Folders, and Mega Sorter. Detailed controls open from these entries.
- Appearance retains Light, Dark, and AMOLED, system wallpaper and dimming,
  app-icon visibility, and a Hidden apps manager. No preference migration is needed.
- Add-ons retains discovery/refresh, enablement, settings, retry, uninstall,
  capability grants, and the event log. Existing security checks remain in place.
- Advanced retains default-launcher setup, module composition controls,
  diagnostics, and the installed app's App info/version.
- Back from a subpage returns to the landing page; Back from the landing page
  returns Home. Page selection survives recreation. A new Home intent returns
  Home. Direct Most Used and add-on settings entry points remain functional.
- Settings has an opaque theme background, even with wallpaper on Home.
  Navigation and controls respect system insets. Pages scroll at small heights
  and large font sizes. Content width is bounded on tablets.
- Rows use a 24dp outline icon and aligned text. Labels and descriptions may
  grow vertically for font scaling; controls must not overlap or be clipped.
- The launcher icon uses two interlocking cobalt/cyan modules on ink navy.
  Color tokens live in `res/values/brand.xml`; geometry is vector-based.
  Adaptive icons support round/rounded/squircle masks, with an Android 13+
  monochrome layer for themed icons. Debug and release retain distinct labels,
  application IDs, and existing signing identities.

## Validation

Check the landing page and all subpages on 320, 375, 414, and 768dp widths;
include enlarged text and Light/Dark/AMOLED. Native capture fixtures and
navigation tests verify the actual Compose screens. Verify the application
manifest resolves an adaptive icon and its themed layer. Review CI screenshots
before merging; a successful compile alone does not establish visual quality.

The unchanged theme roles yield WCAG text/background ratios of 16.52/8.94
(primary/secondary text) for Light, 14.75/10.84 for Dark, and 21.00/14.38 for
AMOLED. Brand art is decorative; text and actionable glyphs use theme roles.

The redraw changes presentation and navigation only. No backup, subscription,
notification, calendar, or weather feature is implied by the reference images.
