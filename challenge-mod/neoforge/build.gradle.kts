import net.fabricmc.loom.api.LoomGradleExtensionAPI
import org.gradle.jvm.tasks.Jar

plugins {
    id("architectury-plugin")
}

val mc: String = project.findProperty("mc")?.toString() ?: "1.21.1"
val info = Versions.info(mc)

if (Versions.isNeoSupported(mc)) {
    project.extensions.extraProperties.set("loom.platform", "neoforge")
    apply(plugin = "dev.architectury.loom")

    val mojmap = (extensions.getByName("loom") as LoomGradleExtensionAPI).officialMojangMappings()

    architectury {
        platformSetupLoomIde()
        neoForge()
    }

    dependencies {
        add("minecraft", "com.mojang:minecraft:${mc}")
        add("mappings", mojmap)
        compileOnly(project(":common"))
        add("neoForge", "net.neoforged:neoforge:${info.neoforge}")
        add("modImplementation", "dev.architectury:architectury-neoforge:${info.architectury}")
        add("include", "dev.architectury:architectury-neoforge:${info.architectury}")
    }

    base {
        archivesName.set("challenge-hud-${mc}-neoforge")
    }

    tasks.jar {
        val commonJar = project(":common").tasks.named<Jar>("jar")
        dependsOn(commonJar)
        from(commonJar.map { zipTree(it.archiveFile) })
    }

    tasks.named<Jar>("remapJar") {
        archiveFileName.set("challenge-hud-${mc}-neoforge.jar")
    }

    tasks.processResources {
        filesMatching("META-INF/neoforge.mods.toml") {
            expand(
                "mcVersion" to mc,
                "neoVersion" to (info.neoforge ?: ""),
                "archVersion" to info.architectury,
            )
        }
    }
} else {
    tasks.configureEach {
        enabled = false
    }
}
