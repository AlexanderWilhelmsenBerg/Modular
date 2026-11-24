plugins { id("com.android.library") }

android {
    namespace = "io.github.alexanderwilhelmsenberg.modular.webui"
    compileSdk = 37
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":module-sdk"))
    testImplementation("junit:junit:4.13.2")
}
