#!/usr/bin/env bash
# Disposable signer used only to exercise both signing configurations in CI.
set -euo pipefail
umask 077
: "${MODULAR_KEYSTORE_PATH:?Missing temporary keystore path}"
: "${MODULAR_KEYSTORE_PASSWORD:?Missing temporary keystore password}"
test ! -e "$MODULAR_KEYSTORE_PATH"
keytool -genkeypair -noprompt -storetype PKCS12 \
  -keystore "$MODULAR_KEYSTORE_PATH" \
  -storepass:env MODULAR_KEYSTORE_PASSWORD -keypass:env MODULAR_KEYSTORE_PASSWORD \
  -alias modular-release -keyalg RSA -keysize 2048 -validity 30 \
  -dname "CN=Disposable Modular CI release" >/dev/null 2>&1
# Copy the release private key to the debug alias, then issue a distinct certificate.
# The temporary copy is encrypted; private key bytes are never exported as plaintext.
cp "$MODULAR_KEYSTORE_PATH" "$MODULAR_KEYSTORE_PATH.copy"
trap 'rm -f -- "$MODULAR_KEYSTORE_PATH.copy"' EXIT
keytool -importkeystore -noprompt \
  -srckeystore "$MODULAR_KEYSTORE_PATH.copy" -srcstoretype PKCS12 \
  -srcstorepass:env MODULAR_KEYSTORE_PASSWORD -srcalias modular-release \
  -destkeystore "$MODULAR_KEYSTORE_PATH" -deststoretype PKCS12 \
  -deststorepass:env MODULAR_KEYSTORE_PASSWORD -destalias modular-debug >/dev/null 2>&1
keytool -selfcert -keystore "$MODULAR_KEYSTORE_PATH" \
  -storepass:env MODULAR_KEYSTORE_PASSWORD -keypass:env MODULAR_KEYSTORE_PASSWORD \
  -alias modular-debug -sigalg SHA256withRSA -validity 30 \
  -dname "CN=Disposable Modular CI debug" >/dev/null 2>&1
