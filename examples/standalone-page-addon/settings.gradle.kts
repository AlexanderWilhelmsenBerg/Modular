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
        val sdkRepository = providers.environmentVariable("MODULAR_SDK_REPOSITORY").orNull
            ?: file("../../build/sdk-repository").absolutePath
        maven { url = uri(sdkRepository) }
        google()
        mavenCentral()
    }
}

rootProject.name = "StandalonePageAddon"
include(":app")
