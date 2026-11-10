plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val modularKeystorePath = providers.environmentVariable("MODULAR_KEYSTORE_PATH").orNull
val modularKeystorePassword = providers.environmentVariable("MODULAR_KEYSTORE_PASSWORD").orNull
if (modularKeystorePath != null) {
    require(!modularKeystorePassword.isNullOrBlank()) { "MODULAR_KEYSTORE_PASSWORD is required" }
    require(file(modularKeystorePath).isFile) { "MODULAR_KEYSTORE_PATH must point to a keystore" }
}

android {
    namespace = "io.github.alexanderwilhelmsenberg.modular.addon.files"
    compileSdk = 37
    defaultConfig {
        applicationId = "io.github.alexanderwilhelmsenberg.modular.addon.files"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
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
    implementation(project(":module-files-ui"))
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("me.zhanghai.android.libarchive:library:1.1.6")
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
