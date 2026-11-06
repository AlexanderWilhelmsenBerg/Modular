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
        val sdkRepository = providers.gradleProperty("modularSdkRepository").orNull
            ?: error("Pass -PmodularSdkRepository=/absolute/path/to/sdk-repository")
        maven { url = uri(sdkRepository) }
        google()
        mavenCentral()
    }
}

rootProject.name = "StandaloneSortAddon"
include(":app")
