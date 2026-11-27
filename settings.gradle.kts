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

rootProject.name = "VLinkDash"

// Pure-Kotlin OBD-II / ELM327 data layer (no Android dependencies, unit-tested on the JVM).
include(":obd-core")

// Set -PcoreOnly=true to build and test only the data layer (no Android SDK needed).
if (providers.gradleProperty("coreOnly").orNull != "true") {
    include(":app")
}
