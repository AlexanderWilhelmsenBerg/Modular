plugins {
    id("com.android.application")
}

// Local/PR builds use Android's disposable debug signer. The manual signed
// workflow supplies the persistent private keystore through environment variables.
val modularKeystorePath = providers.environmentVariable("MODULAR_KEYSTORE_PATH").orNull
val modularKeystorePassword = providers.environmentVariable("MODULAR_KEYSTORE_PASSWORD").orNull
if (modularKeystorePath != null) {
    require(!modularKeystorePassword.isNullOrBlank()) { "MODULAR_KEYSTORE_PASSWORD is required" }
    require(file(modularKeystorePath).isFile) { "MODULAR_KEYSTORE_PATH must point to a keystore" }
}

android {
    namespace =
        "io.github.alexanderwilhelmsenberg.modular.reference.sort.alphabetical"
    compileSdk = 37

    defaultConfig {
        applicationId =
            "io.github.alexanderwilhelmsenberg.modular.reference.sort.alphabetical"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (modularKeystorePath != null) {
            create("persistentDebug") {
                storeFile = file(modularKeystorePath)
                storePassword = modularKeystorePassword
                keyAlias = "modular-debug"
                keyPassword = modularKeystorePassword
                storeType = "PKCS12"
            }
            create("persistentRelease") {
                storeFile = file(modularKeystorePath)
                storePassword = modularKeystorePassword
                keyAlias = "modular-release"
                keyPassword = modularKeystorePassword
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            if (modularKeystorePath != null) signingConfig = signingConfigs.getByName("persistentDebug")
        }
        getByName("release") {
            if (modularKeystorePath != null) signingConfig = signingConfigs.getByName("persistentRelease")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":module-sdk"))

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}

