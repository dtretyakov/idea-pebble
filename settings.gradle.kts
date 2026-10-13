import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

rootProject.name = "idea-pebble"

pluginManagement {
    repositories {
        maven("https://maven-central.storage-download.googleapis.com/maven2/") {
            // Google's mirror of Maven Central, in its place: Central answers bursts of requests
            // with 429 Too Many Requests, and a Gradle build fails on the first one.
            name = "MavenCentralGoogleMirror"
        }
        gradlePluginPortal()
    }
}

plugins {
    id("org.jetbrains.intellij.platform.settings") version "2.18.1"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.PREFER_SETTINGS
    repositories {
        maven("https://maven-central.storage-download.googleapis.com/maven2/") {
            // Google's mirror of Maven Central, in its place: Central answers bursts of requests
            // with 429 Too Many Requests, and a Gradle build fails on the first one.
            name = "MavenCentralGoogleMirror"
        }
        intellijPlatform {
            defaultRepositories()
        }
    }
}
