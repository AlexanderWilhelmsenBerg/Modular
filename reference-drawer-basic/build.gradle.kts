plugins {
    id("com.android.application")
}
android {
    namespace = "io.github.alexanderwilhelmsenberg.modular.reference.drawer.basic"
    compileSdk = 37
    defaultConfig {
        applicationId = "io.github.alexanderwilhelmsenberg.modular.reference.drawer.basic"
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
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation("junit:junit:4.13.2")
}
