import net.fabricmc.loom.api.LoomGradleExtensionAPI
import org.gradle.jvm.tasks.Jar

plugins {
    id("architectury-plugin")
}

val mc: String = project.findProperty("mc")?.toString() ?: "1.21.1"
val info = Versions.info(mc)

if (Versions.isForgeSupported(mc)) {
    project.extensions.extraProperties.set("loom.platform", "forge")
    apply(plugin = "dev.architectury.loom")

    val mojmap = (extensions.getByName("loom") as LoomGradleExtensionAPI).officialMojangMappings()

    architectury {
        platformSetupLoomIde()
        forge()
    }

    dependencies {
        add("minecraft", "com.mojang:minecraft:${mc}")
        add("mappings", mojmap)
        compileOnly(project(":common"))
        add("forge", "net.minecraftforge:forge:${mc}-${info.forge}")
        if (mc == "1.16.5") {
            // architectury 1.x has no maven artifact; resolved from vendor/m2.
            add("modImplementation", "dev.architectury:architectury-forge:1.32.68")
            add("include", "dev.architectury:architectury-forge:1.32.68")
        } else {
            add("modImplementation", "dev.architectury:architectury-forge:${info.architectury}")
            add("include", "dev.architectury:architectury-forge:${info.architectury}")
        }
    }

    base {
        archivesName.set("challenge-hud-${mc}-forge")
    }

    tasks.jar {
        val commonJar = project(":common").tasks.named<Jar>("jar")
        dependsOn(commonJar)
        from(commonJar.map { zipTree(it.archiveFile) })
    }

    tasks.named<Jar>("remapJar") {
        archiveFileName.set("challenge-hud-${mc}-forge.jar")
    }

    tasks.processResources {
        // mc is a project property, not a task input by default: without this the task would
        // be UP-TO-DATE across -Pmc runs and bake stale metadata into the jars.
        inputs.property("mcVersion", mc)
        filesMatching("META-INF/mods.toml") {
            expand(
                "mcVersion" to mc,
                "forgeVersion" to (info.forge ?: ""),
                "archVersion" to info.architectury,
                "fmlRange" to (info.fmlRange ?: ""),
            )
        }
    }
} else {
    tasks.configureEach {
        enabled = false
    }
}
