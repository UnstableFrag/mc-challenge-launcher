pluginManagement {
    repositories {
        mavenCentral()
        maven { url = uri("https://maven.fabricmc.net/") }
        maven { url = uri("https://maven.architectury.dev/") }
        gradlePluginPortal()
    }
    plugins {
        id("fabric-loom") version "1.6.0"
        id("architectury") version "2.1.1"
    }
}

rootProject.name = "challenge-mod"