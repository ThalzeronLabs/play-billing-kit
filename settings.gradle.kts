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

rootProject.name = "play-billing-kit"

include(":billing")
include(":billing-compose")

// JitPack only needs the library; skipping the sample app keeps its builds small and fast.
if (System.getenv("JITPACK") != "true") {
    include(":sample")
}
