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

rootProject.name = "Home-Controller"

// The plugin contract every connector implements.
include(":core")
// Connectors (plugins): one module each. Add a new device family here and in app/build.gradle.kts.
include(":connectors:gree")
// The Android app.
include(":app")
