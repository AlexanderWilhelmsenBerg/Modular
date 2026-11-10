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
include(":addon-most-used")
include(":addon-files")
include(":module-files-ui")

// Independent APK used only by catalog visibility instrumentation tests.
include(":test-launcher-app")



// Independently signed add-on used only by framework acceptance tests.
include(":test-community-module")
