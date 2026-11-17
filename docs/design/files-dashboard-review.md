<!-- Hallmark · pre-emit critique: Philosophy 4 · Hierarchy 4 · Execution 4 · Specificity 5 · Restraint 5 · Variety 4 -->
# Files dashboard design review

Reviewed against Files 0.1.5 source and retained emulator captures. This review
proposes the next visual pass; the accompanying 0.1.6 patch fixes folder actions
and menu input. It does not replace the dashboard layout.

The direction is a compact native storage browser with sharp edges, clear type,
and one consistent icon style. Preserve MaterialTheme typography, scalable text,
Light/Dark/AMOLED, user-chosen folder icons, remembered browsing state, and
recoverable deletion. Web landing-page patterns and font/theme rotation do not
apply. True black is intentional in AMOLED.

## Critical interaction findings

1. **Detached menu input coordinates.** FilesPage used Material dropdowns in
   separate Android popup windows inside a windowless embedded surface. The
   reported vertical touch offset makes visible controls unreliable. The exact
   Samsung cause has not been reproduced; this removes the extra coordinate
   boundary. The exact
   Samsung-specific cause has not been reproduced here; the change removes the
   separate popup coordinate boundary.
   **Correction in 0.1.6:** menus are laid out and receive input inside the Files
   surface. Outside tap, Back and Escape dismiss; long menus scroll within the
   viewport. No coordinate compensation or Core update is required. Physical
   screen-coordinate tap tests cover an inset viewport.
   Source: [menu host](../../module-files-ui/src/main/java/io/github/alexanderwilhelmsenberg/modular/filesui/FilesMenu.kt#L65), [browser menu](../../module-files-ui/src/main/java/io/github/alexanderwilhelmsenberg/modular/filesui/FilesPage.kt#L377).
2. **Folder operations require an undiscoverable selection step.** The folder
   context menu lacked Delete, Copy, Cut and Move; those were page actions after
   selection. **Correction in 0.1.6:** offer them directly on readable/writable
   entries as appropriate. Delete prepares a review of that entry and still
   requires confirmation. Storage roots are not given a delete action.
   Source: [entry actions](../../addon-files/src/main/java/io/github/alexanderwilhelmsenberg/modular/addon/files/FilesPageController.kt#L871).

## Major design findings, in priority order

3. **Storage hierarchy is missing useful information.** The controller supplies
   free/total capacity in location subtitles, but FilesEntryRow only draws the
   name. At the dashboard, a storage location therefore looks like an ordinary
   folder. **Next pass:** use compact storage rows with icon, name, free/total
   space and a thin usage bar. Omit the bar for unknown capacity; show reconnect
   state explicitly. Use data already available, without scanning on entry.
   Source: [location metadata](../../addon-files/src/main/java/io/github/alexanderwilhelmsenberg/modular/addon/files/FilesPageController.kt#L654), [entry row](../../module-files-ui/src/main/java/io/github/alexanderwilhelmsenberg/modular/filesui/FilesPage.kt#L681).
4. **Favorites and Recent have no visual grouping.** Membership lives in the
   subtitle, which the same row renderer omits. Repeated entries can look like
   duplicates. **Next pass:** provide named Favorites and Recent sections with
   compact rows, and show the parent path only where it disambiguates entries.
   Source: [location metadata](../../addon-files/src/main/java/io/github/alexanderwilhelmsenberg/modular/addon/files/FilesPageController.kt#L654), [entry row](../../module-files-ui/src/main/java/io/github/alexanderwilhelmsenberg/modular/filesui/FilesPage.kt#L681).
5. **Mismatched icon sets.** Text glyphs stand in for files, More and Info while
   folders and Trash are custom drawings. Their weights and alignment vary by
   font/device. **Next pass:** use one outlined vector family on a 24dp canvas,
   with 48dp touch targets. Retain user-selected folder symbols and colors.
   Source: [More control](../../module-files-ui/src/main/java/io/github/alexanderwilhelmsenberg/modular/filesui/FilesPage.kt#L661), [file glyph](../../module-files-ui/src/main/java/io/github/alexanderwilhelmsenberg/modular/filesui/FilesPage.kt#L708).
6. **Actions have insufficient priority.** Clean, Info, customization, sorting,
   view mode, settings and diagnostics share one flat overflow list. **Next
   pass:** group folder tools, view controls, and app settings with separators;
   show current sort/view state. Keep Clean and Storage map in the menu as
   requested. During work, use one compact progress strip with the current step,
   count, elapsed time and Stop, without repeating the same label in banners.
   Source: [header actions](../../module-files-ui/src/main/java/io/github/alexanderwilhelmsenberg/modular/filesui/FilesPage.kt#L374), [browser commands](../../addon-files/src/main/java/io/github/alexanderwilhelmsenberg/modular/addon/files/FilesPageController.kt#L762).

## Minor design findings

7. **Wrap-to-two-lines clickable text.** Breadcrumb labels allow two lines,
   breaking the control row's rhythm on narrow screens. **Next pass:** one-line
   ellipsis, scrollable path, full path in Properties/accessibility text.
   Source: [breadcrumb labels](../../module-files-ui/src/main/java/io/github/alexanderwilhelmsenberg/modular/filesui/FilesPage.kt#L451).
8. **Every small control looks like a tile.** The 48dp bordered More square has
   similar emphasis to the content title. **Next pass:** a borderless outlined
   glyph with a 48dp touch target; reserve filled surfaces for selection and
   active operations. This preserves sharp geometry without adding chrome.
   Source: [More control](../../module-files-ui/src/main/java/io/github/alexanderwilhelmsenberg/modular/filesui/FilesPage.kt#L661).

## Recommended layout

- A 48dp toolbar: Files/current folder, Trash count, More.
- One scoped search field; Plus alongside it in writable folders.
- Dashboard: Storage, Favorites, Recent. Dense rows rather than a tile grid.
- Folder: compact breadcrumb/size context, then the file list. Right-aligned
  sizes in Details view, with tabular numerals.
- An active-operation strip only while work is running. Navigation preserves
  the operation and the folder position.
- Empty states give a short reason and the relevant action. Access details and
  diagnostics stay in Info/Settings.

Validate the visual pass at 320, 375, 414 and 768dp, including 1.5x text,
Light/Dark/AMOLED, long names, unknown sizes, offline media, populated storage,
selection, search and ongoing operations. Verify taps at rendered screen
coordinates, not only semantic click handlers.

2 critical · 4 major · 2 minor. The two critical findings are addressed by the
0.1.6 implementation; the six visual recommendations are the proposed next pass.
