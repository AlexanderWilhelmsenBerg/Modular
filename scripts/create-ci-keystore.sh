#!/usr/bin/env bash
# Disposable signer used only to exercise both signing configurations in CI.
set -euo pipefail
umask 077
: "${MODULAR_KEYSTORE_PATH:?Missing temporary keystore path}"
: "${MODULAR_KEYSTORE_PASSWORD:?Missing temporary keystore password}"
test ! -e "$MODULAR_KEYSTORE_PATH"
for variant in debug release; do
  keytool -genkeypair -noprompt -storetype PKCS12 \
    -keystore "$MODULAR_KEYSTORE_PATH" \
    -storepass:env MODULAR_KEYSTORE_PASSWORD -keypass:env MODULAR_KEYSTORE_PASSWORD \
    -alias "modular-$variant" -keyalg RSA -keysize 2048 -validity 30 \
    -dname "CN=Disposable Modular CI $variant" >/dev/null 2>&1
done
