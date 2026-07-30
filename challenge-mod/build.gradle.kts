plugins {
    id("java")
    id("fabric-loom")
    id("architectury-plugin")
}

group = "com.github.mcchallenge"
version = "1.0.0"
java.toolchain.languageVersion.set(JavaLanguageVersion.of(21))

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
    maven("https://maven.architectury.dev/")
}

dependencies {
    minecraft("com.mojang:minecraft:1.21.1")
    mappings("net.fabricmc:yarn:1.21.1+build.1:v2")
    modImplementation("dev.architectury:architectury-api:13.0.8")  // ← версия под 1.21.1, уточни!
    include("dev.architectury:architectury-api:13.0.8")
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes(
            "Mod-Id" to "challengehud",
            "Mod-Name" to "Challenge HUD",
            "Mod-Version" to version.toString(),
            "Fabric-Mod-Loader" to ">=0.14.0"
        )
    }
}