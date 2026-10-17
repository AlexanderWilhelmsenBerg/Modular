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
include(":reference-sort-alphabetical")
include(":reference-home-minimal")
include(":reference-drawer-basic")
