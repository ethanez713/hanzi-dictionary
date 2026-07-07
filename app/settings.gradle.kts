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
        // Scoped to requery only: its FTS5-enabled SQLite (com.github.requery:sqlite-android)
        // is published via JitPack, not Maven Central. Content filter keeps every other
        // dependency resolving from the trusted repos above.
        maven("https://jitpack.io") {
            content { includeGroup("com.github.requery") }
        }
    }
}

rootProject.name = "hanzi-dictionary"

include(":core")
include(":androidApp")
