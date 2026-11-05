# Android signing and parallel installation

Debug and release use different application IDs **and** separate persistent signing
identities. Certificate differences alone do not allow two installations of the
same application ID.

| Variant | Core application ID | Display name | Keystore alias |
| --- | --- | --- | --- |
| Debug | `io.github.alexanderwilhelmsenberg.modular.debug` | Modular Debug | `modular-debug` |
| Release | `io.github.alexanderwilhelmsenberg.modular` | Modular | `modular-release` |

Both keys/certificates live in one private PKCS12 keystore. The reference sort
module also has a `.debug` ID suffix and uses the matching variant's signing key,
so Core's same-signer trust policy continues to work. With both reference variants
installed, the module registry can discover both; enable the intended variant and
disable the other to avoid ambiguous provider selection.

## GitHub setup

Create these **repository Actions secrets** using the separately delivered private
bundle (never commit the bundle):

- `MODULAR_KEYSTORE_BASE64`: contents of `keystore.b64`.
- `MODULAR_KEYSTORE_PASSWORD`: contents of `keystore.password`.

Alternatively, use an authenticated GitHub CLI and run the bundle's
`upload-secrets.sh`. It refuses to overwrite existing signing secrets by default.
The public certificate fingerprints in [signing-certificates.json](signing-certificates.json)
are safe to commit and are checked before distributing signed APKs.

After `.github/workflows/signed-apks.yml` is on the default branch, run **Signed
APKs** from the Actions tab, selecting the trusted branch/tag to build. The
`modular-signed-apks` artifact contains persistent debug and release APKs for Core
and the reference module. The workflow fails if secrets are absent or the
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
gradle :app:assembleDebug :app:assembleRelease \
  :reference-sort-alphabetical:assembleDebug :reference-sort-alphabetical:assembleRelease \
  --no-configuration-cache
python3 scripts/verify-signed-apks.py --expected-certificates docs/signing-certificates.json
```

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
