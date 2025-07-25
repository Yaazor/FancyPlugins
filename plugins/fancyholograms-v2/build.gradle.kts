import net.minecrell.pluginyml.bukkit.BukkitPluginDescription
import net.minecrell.pluginyml.paper.PaperPluginDescription

plugins {
    id("java-library")
    id("maven-publish")

    id("xyz.jpenilla.run-paper")
    id("com.gradleup.shadow")
    id("de.eldoria.plugin-yml.paper")
    id("io.papermc.hangar-publish-plugin")
    id("com.modrinth.minotaur")
}

runPaper.folia.registerTask()

val supportedVersions =
    listOf(
        "1.19.4",
        "1.20",
        "1.20.1",
        "1.20.2",
        "1.20.3",
        "1.20.4",
        "1.20.5",
        "1.20.6",
        "1.21",
        "1.21.1",
        "1.21.2",
        "1.21.3",
        "1.21.4",
        "1.21.5",
        "1.21.6",
    )

allprojects {
    group = "de.oliver"
    version = getFHVersion()
    description = "Simple, lightweight and fast hologram plugin using display entities"

    repositories {
        mavenLocal()
        mavenCentral()

        maven(url = "https://repo.papermc.io/repository/maven-public/")
        maven("https://s01.oss.sonatype.org/content/repositories/snapshots/")

        maven(url = "https://repo.fancyinnovations.com/snapshots")
        maven(url = "https://repo.fancyinnovations.com/releases")
        maven(url = "https://repo.lushplugins.org/releases")
        maven(url = "https://repo.viaversion.com/")
        maven(url = "https://repo.opencollab.dev/main/")
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.7-R0.1-SNAPSHOT")

    implementation(project(":plugins:fancyholograms-v2:api"))
    implementation(project(":plugins:fancyholograms-v2:implementation_1_20_4", configuration = "reobf"))
    implementation(project(":plugins:fancyholograms-v2:implementation_1_20_2", configuration = "reobf"))
    implementation(project(":plugins:fancyholograms-v2:implementation_1_20_1", configuration = "reobf"))
    implementation(project(":plugins:fancyholograms-v2:implementation_1_19_4", configuration = "reobf"))

    rootProject.subprojects
        .filter { it.path.startsWith(":libraries:packets:implementations") }
        .forEach { implementation(project(it.path)) }
    implementation(project(":libraries:packets"))
    implementation(project(":libraries:packets:packets-api"))
    implementation(project(":libraries:common"))
    implementation(project(":libraries:jdb"))
    implementation("de.oliver.FancyAnalytics:java-sdk:0.0.3")
    implementation("de.oliver.FancyAnalytics:mc-api:0.1.7")
    implementation("de.oliver.FancyAnalytics:logger:0.0.6")

    compileOnly(project(":plugins:fancynpcs:fn-api"))
    compileOnly("org.lushplugins:ChatColorHandler:5.1.6")
    compileOnly("org.geysermc.floodgate:api:2.2.4-SNAPSHOT")
}

paper {
    name = "FancyHolograms"
    main = "de.oliver.fancyholograms.FancyHolograms"
    bootstrapper = "de.oliver.fancyholograms.loaders.FancyHologramsBootstrapper"
    loader = "de.oliver.fancyholograms.loaders.FancyHologramsLoader"
    foliaSupported = true
    version = getFHVersion()
    description = "Simple, lightweight and fast hologram plugin using display entities"
    apiVersion = "1.19"
    load = BukkitPluginDescription.PluginLoadOrder.POSTWORLD
    serverDependencies {
        register("FancyNpcs") {
            required = false
            load = PaperPluginDescription.RelativeLoadOrder.BEFORE
        }
        register("MiniPlaceholders") {
            required = false
            load = PaperPluginDescription.RelativeLoadOrder.BEFORE
        }
        register("PlaceholderAPI") {
            required = false
            load = PaperPluginDescription.RelativeLoadOrder.BEFORE
        }
        register("floodgate") {
            required = false
            load = PaperPluginDescription.RelativeLoadOrder.BEFORE
            joinClasspath = true
        }
    }
}

tasks {
    runServer {
        minecraftVersion("1.21.7")

        downloadPlugins {
            modrinth("fancynpcs", "2.5.2")
            hangar("ViaVersion", "5.4.0")
            hangar("ViaBackwards", "5.4.0")
            modrinth("multiverse-core", "5.0.2")
            hangar("PlaceholderAPI", "2.11.6")
//            modrinth("DecentHolograms", "2.8.12")
        }
    }

    shadowJar {
        archiveClassifier.set("")
        archiveBaseName.set("FancyHolograms")

        dependsOn(":plugins:fancyholograms-v2:api:shadowJar")
    }

    compileJava {
        options.encoding = Charsets.UTF_8.name() // We want UTF-8 for everything
        options.release = 21
        // For cloud-annotations, see https://cloud.incendo.org/annotations/#command-components
        options.compilerArgs.add("-parameters")
    }

    javadoc {
        options.encoding = Charsets.UTF_8.name() // We want UTF-8 for everything
    }

    processResources {
        filteringCharset = Charsets.UTF_8.name() // We want UTF-8 for everything

        val props = mapOf(
            "description" to project.description,
            "version" to getFHVersion(),
            "commit_hash" to gitCommitHash.get(),
            "channel" to (System.getenv("RELEASE_CHANNEL") ?: "").ifEmpty { "undefined" },
            "platform" to (System.getenv("RELEASE_PLATFORM") ?: "").ifEmpty { "undefined" }
        )

        inputs.properties(props)

        filesMatching("paper-plugin.yml") {
            expand(props)
        }

        filesMatching("version.yml") {
            expand(props)
        }
    }
}

tasks.publishAllPublicationsToHangar {
    dependsOn("shadowJar")
}

tasks.modrinth {
    dependsOn("shadowJar")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

val gitCommitHash: Provider<String> = providers.exec {
    commandLine("git", "rev-parse", "HEAD")
}.standardOutput.asText.map { it.trim() }

val gitCommitMessage: Provider<String> = providers.exec {
    commandLine("git", "log", "-1", "--pretty=%B")
}.standardOutput.asText.map { it.trim() }

fun getFHVersion(): String {
    return file("VERSION").readText()
}

hangarPublish {
    publications.register("plugin") {
        version = project.version as String
        id = "FancyHolograms"
        channel = "Alpha"

        apiKey.set(System.getenv("HANGAR_PUBLISH_API_TOKEN"))

        platforms {
            paper {
                jar = tasks.shadowJar.flatMap { it.archiveFile }
                platformVersions = supportedVersions
            }
        }

        changelog = gitCommitMessage.get()
    }
}

modrinth {
    token.set(System.getenv("MODRINTH_PUBLISH_API_TOKEN"))
    projectId.set("fancyholograms")
    versionNumber.set(getFHVersion())
    versionType.set("alpha")
    uploadFile.set(file("build/libs/${project.name}-${getFHVersion()}.jar"))
    gameVersions.addAll(supportedVersions)
    loaders.add("paper")
    loaders.add("folia")
    changelog.set(gitCommitMessage.get())
}