plugins {
    id("com.android.library")
    id("maven-publish")
}

group = "io.github.alexanderwilhelmsenberg.modular"
version = "0.3.0"

android {
    namespace = "io.github.alexanderwilhelmsenberg.modular.moduleapi"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
    }

    buildFeatures {
        aidl = true
    }

    publishing {
        singleVariant("release")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation("junit:junit:4.13.2")
}


afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "io.github.alexanderwilhelmsenberg.modular"
                artifactId = "module-api"
                version = "0.3.0"
            }
        }
        repositories {
            maven {
                name = "sdkRepository"
                url = uri(rootProject.layout.buildDirectory.dir("sdk-repository"))
            }
        }
    }
}
