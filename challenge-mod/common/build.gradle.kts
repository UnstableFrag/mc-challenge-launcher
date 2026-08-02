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
    if (mc == "1.16.5") {
        // architectury 1.x has no maven artifact (packages me.shedaniel.*); use the vendored jar.
        modCompileOnly(files(rootProject.file("vendor/architectury-1.32.68-fabric.jar")))
        // slf4j is not exposed on the loom compile classpath for 1.16.5 (it ships at runtime
        // via log4j-slf4j18-impl in the game libs, so compileOnly is sufficient).
        compileOnly("org.slf4j:slf4j-api:1.8.0-beta4")
    } else {
        modCompileOnly("dev.architectury:architectury:${info.architectury}")
    }
}

sourceSets {
    main {
        java.setSrcDirs(listOf("src/main/java", "src/$era/java"))
    }
}
