pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        // Released lsp-kmp / lsp-edit from Maven Central; local builds are no
        // longer picked up, so the pinned versions are what everyone gets.
        mavenCentral()
        google()
    }
}

rootProject.name = "ImCode"