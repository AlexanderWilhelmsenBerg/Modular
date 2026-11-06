# Standalone sort add-on

This is a separate Android project. It depends on published Modular Android libraries, not the Core source tree or Core signing key.

Extract the `modular-addon-sdk` artifact from a successful Android CI run into a directory such as `sdk-repository`, then run from the Modular checkout or use an absolute path to this project:

```sh
gradle -p examples/standalone-sort-addon \
  -PmodularSdkRepository=/absolute/path/to/sdk-repository \
  :app:assembleDebug
```

The APK is at `app/build/outputs/apk/debug/app-debug.apk` within this project. Its protocol-v4 descriptor advertises `modular.apps.sort/v1` and a per-instance `reverse` Boolean setting. The service pins the certificate identities of the owner's persistently signed Core debug and release APKs from [signing-certificates.json](../../docs/signing-certificates.json). Change these pins if testing with a Core APK signed by a different certificate. Android signs this add-on with its own normal debug key unless you configure another signing key.

See [Add-on development](../../docs/addon-development.md) for the wire contract, SDK hooks, and setup steps.
