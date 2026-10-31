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

rootProject.name = "fluidframes"

include(
    ":app",
    ":core-capture",
    ":core-telemetry",
    ":core-overlay",
    ":core-vulkan",
    ":core-enn",
)
