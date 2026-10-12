plugins {
    id("com.android.application")
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
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":module-api"))

    testImplementation("junit:junit:4.13.2")
}
