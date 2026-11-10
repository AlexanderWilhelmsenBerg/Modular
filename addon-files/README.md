# Modular Files

Modular Files is a separate Android add-on that adds a native file-manager page
beside Modular Home. It manages phone shared storage and supported SD/USB
locations. Network storage and scheduled cleanup are outside v1.

## Install and place the page

1. Install matching signed Core and Files APK variants from the same successful
   build: debug with debug, or release with release. The Files icon is a folder
   with Modular's mark in its lower-right corner.
2. Enable Modular Files in Core's module settings if it is disabled.
3. In Core Settings → Productivity → Home screen, choose **Add Modular Files to
   Home screens**, select a side of Home, and adjust the screen order as needed.
4. Swipe to Files and choose a folder through Android's picker. **Full storage
   access** is optional in Files settings; selected-folder mode works without it.

The Files app can also open its own browser/setup screen from Android's app list.
Storage permission belongs to Files, not Core. Android still protects other
apps' private data even when Full storage access is enabled.

## File management and cleanup

Open a location, browse folders, search locally or across connected locations,
and long-press a file for its available actions. Copy and Cut stage references;
Paste chooses the destination and resolves collisions before changing files.

**Clean** opens the current folder's cleanup view. Start a scan explicitly,
choose which candidates to remove, and review every affected file. Numbered
copies with conflicting dates require a keeper choice. Normal **Delete** moves
reviewed files to trash. Trash does not free the space until it is emptied.

The trashcan shows grey when empty, blue for up to 20 files, and red above 20.
Trash lists original locations and offers restore and a separately confirmed
permanent empty operation. Unavailable media remains represented in trash;
reconnect it before restoring or emptying its entries.

## Documents and archives

Markdown opens in the native viewer/editor with draft recovery and conflict
checks. PDFs open in the native viewer; text/password capabilities depend on
the Android platform. Unsupported formats can use another installed app.

Extraction supports single-volume ZIP, 7z, RAR4/RAR5, TAR, and TAR.GZ. Encrypted
formats depend on the linked libarchive reader; unsupported encryption and
multipart archives fail explicitly. **Move archive to trash after successful
extraction** applies only after complete verified output; failed, canceled, or
partial extraction retains the source archive.

See the [accepted specification](../docs/product/file-manager-cleaner-v1.md),
[architecture and compatibility notes](../docs/implementation/files-addon-architecture.md),
and [validation ledger](../docs/implementation/files-addon-validation.md) for
precise scope and evidence, including device/provider checks not yet verified.
