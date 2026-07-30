plugins {
    id("java")
    id("fabric-loom")
    id("architectury") version "3.4.160"
}

group = "com.github.mcchallenge"
version = "1.0.0"
java.toolchain.languageVersion.set(JavaLanguageVersion.of(21))

architectury {
    mappings = "yarn"
    commonSourceSets = listOf("common")
}

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
    maven("https://maven.architectury.dev/")
}

dependencies {
    minecraft "com.mojang:minecraft:1.21.1"
    mappings "net.fabricmc:yarn:1.21.1+build.1:v2"
    modImplementation "dev.architectury:architectury-api:9.1.13"
    modImplementation "com.mojang:minecraft:1.21.1"
    include "dev.architectury:architectury-api:9.1.13"
}

loom {
    modid = "challengehud"
    mavenUrls = listOf("https://maven.architectury.dev/")
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes(
            "Mod-Id" to "challengehud",
            "Mod-Name" to "Challenge HUD",
            "Mod-Version" to version.toString(),
            "Architectury-Mod-Version" to "9.1.13",
            "Fabric-Mod-Loader" to ">=0.14.0"
        )
    }
    from(configurations.runtimeClasspath.filter { it.name.startsWith("architectury-api") }.map { zipTree(it) })
}