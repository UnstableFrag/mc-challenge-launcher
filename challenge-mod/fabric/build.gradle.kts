import net.fabricmc.loom.api.LoomGradleExtensionAPI
import org.gradle.jvm.tasks.Jar

plugins {
    id("dev.architectury.loom")
    id("architectury-plugin")
}

val mc: String = project.findProperty("mc")?.toString() ?: "1.21.1"
val info = Versions.info(mc)

val mojmap = (extensions.getByName("loom") as LoomGradleExtensionAPI).officialMojangMappings()

architectury {
    platformSetupLoomIde()
    fabric()
}

dependencies {
    "minecraft"("com.mojang:minecraft:${mc}")
    "mappings"(mojmap)
    compileOnly(project(":common"))
    modImplementation("net.fabricmc:fabric-loader:0.16.14")
    modApi("dev.architectury:architectury-fabric:${info.architectury}")
    include("dev.architectury:architectury-fabric:${info.architectury}")
}

base {
    archivesName.set("challenge-hud-${mc}")
}

tasks.jar {
    val commonJar = project(":common").tasks.named<Jar>("jar")
    dependsOn(commonJar)
    from(commonJar.map { zipTree(it.archiveFile) })
}

tasks.remapJar {
    archiveFileName.set("challenge-hud-${mc}.jar")
}

tasks.processResources {
    filesMatching("fabric.mod.json") {
        expand(
            "mcVersion" to mc,
            "archVersion" to info.architectury,
            "loaderVersion" to info.loader,
        )
    }
}
