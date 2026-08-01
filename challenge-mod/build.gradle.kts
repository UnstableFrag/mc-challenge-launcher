plugins {
    id("architectury-plugin") version "3.5.169"
    id("dev.architectury.loom") version "1.17.491" apply false
    java
}

group = "com.github.mcchallenge"
version = "1.0.0"

val mc: String = project.findProperty("mc")?.toString() ?: "1.21.1"
val info = Versions.info(mc)

architectury {
    minecraft = mc
}

subprojects {
    apply(plugin = "java")

    group = rootProject.group
    version = rootProject.version

    repositories {
        mavenCentral()
        maven("https://maven.architectury.dev/")
        maven("https://maven.fabricmc.net/")
        maven("https://maven.neoforged.net/releases/")
        maven("https://maven.minecraftforge.net/")
    }

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release.set(info.release)
    }
}
