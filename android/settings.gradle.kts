// The RedDot Android app: a voice bubble for any text field, plus a notes list.
// Build:  cd android && ./gradlew assembleDebug     (needs a JDK 17+ and the Android SDK, see README)
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
rootProject.name = "RedDot"
include(":app")
