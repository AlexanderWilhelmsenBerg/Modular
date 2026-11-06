pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Modular"
include(":app")
include(":module-api")
include(":module-sdk")
include(":reference-sort-alphabetical")

// Independent APK used only by catalog visibility instrumentation tests.
include(":test-launcher-app")



// Independently signed add-on used only by framework acceptance tests.
include(":test-community-module")
