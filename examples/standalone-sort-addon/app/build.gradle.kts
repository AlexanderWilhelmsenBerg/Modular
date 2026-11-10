plugins {
    id("com.android.application")
}

android {
    namespace = "dev.modular.examples.standalonesort"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.modular.examples.standalonesort"
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
    implementation("io.github.alexanderwilhelmsenberg.modular:module-api:0.3.0")
    implementation("io.github.alexanderwilhelmsenberg.modular:module-sdk:0.3.0")
}
