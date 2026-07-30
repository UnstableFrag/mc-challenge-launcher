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
    mappings("net.fabricmc:yarn:1.21.1+build.3:v2")
    modImplementation("net.fabricmc.fabric-api:fabric-api:0.116.15+1.21.1")
    modImplementation("dev.architectury:architectury-fabric:15.0.3")
    include("dev.architectury:architectury-fabric:15.0.3")
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