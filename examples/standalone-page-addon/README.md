# Standalone native page add-on

This independent Android project consumes the published `module-api` and
`module-sdk` 0.4.0 artifacts. It has no project dependency on Core or Files.
After publishing the SDK into `../../build/sdk-repository`, build both variants:

```sh
gradle -p examples/standalone-page-addon :app:assembleV1Debug :app:assembleV2Debug
```

Set `MODULAR_SDK_REPOSITORY` to another Maven repository path when needed.
The APKs are `app/build/outputs/apk/v1/debug/app-v1-debug.apk` and
`app/build/outputs/apk/v2/debug/app-v2-debug.apk` relative to this project.
Both variants have the same package and debug signing key. v1 has versionCode 1;
v2 has versionCode 2 and adds a native control. The provider renders Android
Views in its own process using `WorkspaceSurfaceEndpoint` on API 33+. On API
29–32, Core offers an explicit button that opens this add-on's declared
`StandaloneSurfaceActivity`; v2 adds an ordinary Activity dialog there too.
