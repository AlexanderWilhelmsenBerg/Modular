plugins {
    id("com.android.application")
}

// Both variants use the same Android debug key. CI signs Core with its
// separate persistent identity and installs v1 before the instrumentation run.
android {
    namespace = "io.github.alexanderwilhelmsenberg.modular.testsurface"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.alexanderwilhelmsenberg.modular.testsurface"
        minSdk = 30
        targetSdk = 36
    }
    buildFeatures { buildConfig = true }
    flavorDimensions += "surfaceRevision"
    productFlavors {
        create("v1") {
            dimension = "surfaceRevision"
            versionCode = 1
            versionName = "1.0"
            buildConfigField("int", "SURFACE_REVISION", "1")
        }
        create("v2") {
            dimension = "surfaceRevision"
            versionCode = 2
            versionName = "2.0"
            buildConfigField("int", "SURFACE_REVISION", "2")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("io.github.alexanderwilhelmsenberg.modular:module-api:0.4.0")
    implementation("io.github.alexanderwilhelmsenberg.modular:module-sdk:0.4.0")
}
