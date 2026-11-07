# Android signing and parallel installation

Debug and release use different application IDs **and** distinct persistent signing
certificates. Certificate differences alone do not allow two installations of the
same application ID.

| Variant | Core application ID | Display name | Keystore alias |
| --- | --- | --- | --- |
| Debug | `io.github.alexanderwilhelmsenberg.modular.debug` | Modular Debug | `modular-debug` |
| Release | `io.github.alexanderwilhelmsenberg.modular` | Modular | `modular-release` |

One private RSA-3072 key is reused under two aliases in one PKCS12 keystore.
Each alias has a different self-signed certificate; their SHA-256 certificate
fingerprints differ, while their public-key fingerprints match. This is the
requested shared-key setup: possession of the private key enables signing both
variants. Optional legacy/reference add-ons retain their existing package IDs,
including `.debug` suffixes, and use the matching variant's signing key,
so Core's same-signer trust policy continues to work. With both reference variants
installed, the module registry can discover both; enable the intended variant and
disable the other to avoid ambiguous provider selection.

## GitHub setup

Generate and retain the private keystore on the owner's computer, outside the
repository. The pinned public fingerprints correspond to the owner's locally
generated identities; the earlier assistant-generated bundle is superseded.
Never commit the keystore or its password.

In GitHub, open **Settings > Secrets and variables > Actions > Secrets** and
create or update these **repository Actions secrets** (not Variables):

- `MODULAR_KEYSTORE_BASE64`: Base64 encoding of the complete `modular-signing.p12` file.
- `MODULAR_KEYSTORE_PASSWORD`: the existing keystore password, also used for both key entries.

For manual entry from PowerShell, copy the keystore encoding to the clipboard,
then paste it into the corresponding GitHub secret:

```powershell
[Convert]::ToBase64String(
    [System.IO.File]::ReadAllBytes('C:\Development\.modular\modular-signing.p12')
) | Set-Clipboard
```

Enter the password secret from the owner's password manager. No alias secrets
are needed; the aliases are fixed in the build configuration. Clear sensitive
clipboard entries afterward and keep an encrypted backup of the keystore.
OpenSSL is not needed for key generation or manual secret upload.

The public certificate fingerprints in [signing-certificates.json](signing-certificates.json)
are safe to commit and are checked before distributing signed APKs. These include
SHA-256 of each DER certificate and SHA-256 of its DER SubjectPublicKeyInfo.
The CI verifier uses OpenSSL to extract that public-key representation.

After `.github/workflows/signed-apks.yml` is on the default branch, run **Signed
APKs** from the Actions tab, selecting the trusted branch/tag to build. The
`modular-signed-apks` artifact contains persistent Core debug and release APKs.
Select `include_reference_addons` only for SDK/compatibility testing; that run
also uploads `modular-reference-addons` with Alphabetical Sort and legacy Most
Used debug/release APKs. Ordinary Most Used is built into Core and needs no
separate APK. The workflow fails if secrets are absent or the
certificates do not match the pinned public fingerprints. It never falls back to
unsigned or disposable signing for distribution.

Ordinary PR CI receives no persistent signing secrets. Its `modular-core-debug-ci`
artifact uses a disposable signer and is for verification, not repeatable device
updates. The emulator job separately exercises both signing configurations with
temporary certificates, verifies application IDs, debug flags and signatures,
and installs release alongside debug while running the existing device tests.

## Local persistent builds

Set `MODULAR_KEYSTORE_PATH` to an absolute path outside the checkout and
`MODULAR_KEYSTORE_PASSWORD` to the keystore password, then build:

```sh
gradle :app:assembleDebug :app:assembleRelease --no-configuration-cache
python3 scripts/verify-signed-apks.py --expected-certificates docs/signing-certificates.json --core-only
```

For optional reference add-on builds, assemble both variants of
`reference-sort-alphabetical` and `addon-most-used`, then run the verifier without
`--core-only`. Its default still checks all six APKs, as Android CI does.

Without those variables local debug uses Android's default debug key, while local
release is unsigned. Do not distribute that unsigned release output.

## Key continuity and existing installs

Keep a secure backup of the keystore and password: future updates must retain the
same application ID and signing identity. These generated keys do not match older
disposable CI certificates. The new `.debug` Core can coexist with the old
unsuffixed install; installing the newly signed release over an old unsuffixed
APK with another signer requires removing that old installation first, which
removes its local data. No uninstall is performed automatically.

This setup is for APK distribution. If Play App Signing is introduced later,
distinguish Google's app-signing certificate from the upload key and review
same-signer module trust before mixing distribution channels.

## Installation warnings and restricted settings

For normal use choose the signed **release** Core APK; debug builds are intended
for development. Reference add-ons require a matching variant when tested.
Neither variant guarantees acceptance
by Play Protect or exemption from Android's restricted settings. Keep existing
keys and package identities: rotating them is not a fix for a classification.

A **Harmful app blocked** warning is different from an unknown-app scan prompt.
Review the affected APK and dependencies, then request a classification review
through [Google's Play Protect appeal form](https://support.google.com/googleplay/android-developer/contact/protectappeals)
if the warning appears incorrect. Do not disable Play Protect as a fix.
The form currently asks for the affected package and the SHA-256 of the APK
uploaded to VirusTotal. Sharing/submitting the APK is a separate owner action.
Use the APK file hash, not its signing certificate fingerprint or the ZIP hash:

```powershell
Get-FileHash -Algorithm SHA256 '.\app-debug.apk'
```

Debug package names:

- Core: `io.github.alexanderwilhelmsenberg.modular.debug`
- Legacy Most Used reference add-on: `io.github.alexanderwilhelmsenberg.modular.addon.sort.mostused.debug`

Suggested review description (use only for the matching reviewed build):

> Modular is an Android launcher. Core requests REQUEST_DELETE_PACKAGES
> for user-confirmed uninstall actions and PACKAGE_USAGE_STATS for optional,
> locally ranked Most Used suggestions following explicit user permission.
> The reviewed Core APK declares no Internet, SMS, accessibility-service, or notification-listener
> access. Please review the submitted APK's harmful-app classification. The warning
> was observed on Android 17 / One UI 9. Source: https://github.com/AlexanderWilhelmsenBerg/Modular

The earlier APKs from signed run `42395463536` were inspected: signatures match
the pinned certificates, the release APKs are not debuggable, and their declared
permissions matched the old add-on-based design (Core also had AndroidX's internal
signature-protected receiver permission). This verifies identity and manifest
contents at that revision, not the current Core manifest, Google's private
detection rules, or a complete malware assessment. This does not claim Play or
F-Droid publication.

For a grey Usage Access switch or missing App info menu, follow the
[Most Used setup guide](addons/most-used.md). Core now owns this optional access;
the old add-on's grant cannot transfer. Usage Access is special access, so
Android's ordinary Permissions page can correctly say no permissions are required.

Reference: [Google's developer guidance for Play Protect warnings](https://developers.google.com/android/play-protect/warning-dev-guidance).
