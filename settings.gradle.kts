// settings.gradle.kts — tells Gradle which modules make up the project and
// where to download plugins and libraries from.

pluginManagement {
    repositories {
        // Google's repository hosts the Android Gradle plugin and AndroidX libraries.
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

rootProject.name = "btPhoneboard"
include(":app")
