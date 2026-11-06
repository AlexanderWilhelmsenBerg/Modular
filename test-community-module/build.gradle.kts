plugins {
    id("com.android.application")
}

// An ordinary, separately signed module APK. In CI Core uses a disposable
// persistent debug identity while this fixture uses Android's debug signer.
android {
    namespace = "io.github.alexanderwilhelmsenberg.modular.testcommunity"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.alexanderwilhelmsenberg.modular.testcommunity"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":module-sdk"))
}
