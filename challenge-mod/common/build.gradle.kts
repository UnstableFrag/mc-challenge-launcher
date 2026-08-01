import net.fabricmc.loom.api.LoomGradleExtensionAPI

plugins {
    id("dev.architectury.loom")
    id("architectury-plugin")
}

val mc: String = project.findProperty("mc")?.toString() ?: "1.21.1"
val info = Versions.info(mc)
val era = Versions.eraOf(mc)

val mojmap = (extensions.getByName("loom") as LoomGradleExtensionAPI).officialMojangMappings()

architectury {
    common("fabric", "forge", "neoforge")
    platformSetupLoomIde()
}

dependencies {
    "minecraft"("com.mojang:minecraft:${mc}")
    "mappings"(mojmap)
    modImplementation("net.fabricmc:fabric-loader:0.16.14")
    modCompileOnly("dev.architectury:architectury:${info.architectury}")
}

sourceSets {
    main {
        java.setSrcDirs(listOf("src/main/java", "src/$era/java"))
    }
}
