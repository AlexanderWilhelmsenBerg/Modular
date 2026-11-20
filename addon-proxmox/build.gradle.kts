plugins {
    id("com.android.application")
}

val modularKeystorePath = providers.environmentVariable("MODULAR_KEYSTORE_PATH").orNull
val modularKeystorePassword = providers.environmentVariable("MODULAR_KEYSTORE_PASSWORD").orNull
if (modularKeystorePath != null) {
    require(!modularKeystorePassword.isNullOrBlank()) { "MODULAR_KEYSTORE_PASSWORD is required" }
    require(file(modularKeystorePath).isFile) { "MODULAR_KEYSTORE_PATH must point to a keystore" }
}

android {
    namespace = "io.github.alexanderwilhelmsenberg.modular.addon.proxmox"
    compileSdk = 37
    defaultConfig {
        applicationId = "io.github.alexanderwilhelmsenberg.modular.addon.proxmox"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
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

dependencies { implementation(project(":module-web-ui")) }
