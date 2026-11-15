// ONA Controls: a standalone app (floating Winlator-style controls over other apps).
// Its own Gradle build, separate from the launcher; CI: .github/workflows/ona_controls.yml
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

rootProject.name = "OnaControls"
include(":app")
