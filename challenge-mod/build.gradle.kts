plugins {
    id("java")
    id("fabric-loom")
    id("architectury-plugin")
}

group = "com.github.mcchallenge"

val mcVersion: String = project.findProperty("mc")?.toString() ?: "1.21.1"

data class V(val yarn: String, val fabricApi: String, val architectury: String, val release: Int, val loader: String)

val VERSIONS = mapOf(
    "1.20.1" to V("1.20.1+build.10", "0.92.11+1.20.1", "9.2.14", 17, ">=0.14.0"),
    "1.20.2" to V("1.20.2+build.4", "0.91.6+1.20.2", "10.1.20", 17, ">=0.14.0"),
    "1.20.4" to V("1.20.4+build.3", "0.97.3+1.20.4", "11.1.17", 17, ">=0.14.0"),
    "1.20.6" to V("1.20.6+build.3", "0.100.8+1.20.6", "12.1.4", 21, ">=0.15.0"),
    "1.21" to V("1.21+build.9", "0.102.0+1.21", "13.0.11", 21, ">=0.15.0"),
    "1.21.1" to V("1.21.1+build.3", "0.116.15+1.21.1", "13.0.11", 21, ">=0.15.0"),
    "1.21.2" to V("1.21.2+build.1", "0.106.1+1.21.2", "14.0.4", 21, ">=0.16.0"),
    "1.21.3" to V("1.21.3+build.2", "0.114.1+1.21.3", "14.0.4", 21, ">=0.16.0"),
    "1.21.4" to V("1.21.4+build.8", "0.119.4+1.21.4", "15.0.3", 21, ">=0.16.0"),
    "1.21.5" to V("1.21.5+build.1", "0.128.2+1.21.5", "16.1.4", 21, ">=0.16.0"),
    "1.21.6" to V("1.21.6+build.1", "0.128.2+1.21.6", "17.0.6", 21, ">=0.16.0"),
    "1.21.7" to V("1.21.7+build.8", "0.129.0+1.21.7", "17.0.8", 21, ">=0.16.0"),
    "1.21.8" to V("1.21.8+build.1", "0.136.1+1.21.8", "17.0.8", 21, ">=0.16.0"),
    "1.21.9" to V("1.21.9+build.1", "0.134.1+1.21.9", "18.0.5", 21, ">=0.16.0"),
    "1.21.10" to V("1.21.10+build.3", "0.138.4+1.21.10", "18.0.8", 21, ">=0.16.0"),
    "1.21.11" to V("1.21.11+build.6", "0.141.6+1.21.11", "19.0.1", 21, ">=0.16.0"),
)

val cfg: V = VERSIONS[mcVersion] ?: error("Unsupported mc version: $mcVersion")

version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
    maven("https://maven.architectury.dev/")
}

dependencies {
    minecraft("com.mojang:minecraft:${mcVersion}")
    mappings("net.fabricmc:yarn:${cfg.yarn}:v2")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${cfg.fabricApi}")
    modImplementation("dev.architectury:architectury-fabric:${cfg.architectury}")
    include("dev.architectury:architectury-fabric:${cfg.architectury}")
}

tasks.withType<JavaCompile> {
    options.release.set(cfg.release)
}

tasks.processResources {
    filesMatching("fabric.mod.json") {
        expand(
            "mcVersion" to mcVersion,
            "archVersion" to cfg.architectury,
            "loaderVersion" to cfg.loader,
        )
    }
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    archiveFileName.set("challenge-hud-${mcVersion}.jar")
    manifest {
        attributes(
            "Mod-Id" to "challengehud",
            "Mod-Name" to "Challenge HUD",
            "Mod-Version" to version.toString(),
            "Fabric-Mod-Loader" to ">=0.14.0",
            "Archived-Classifier" to "universal",
            "Automatic-Module-Name" to "challengehud",
        )
    }
}

tasks.remapJar {
    archiveFileName.set("challenge-hud-${mcVersion}.jar")
}
