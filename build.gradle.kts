import Versioning.determineVersion
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

fun properties(key: String) = providers.gradleProperty(key)
fun environment(key: String) = providers.environmentVariable(key)

plugins {
    id("java") // Java support
    alias(libs.plugins.kotlin) // Kotlin support
    alias(libs.plugins.intelliJPlatform) // IntelliJ Platform Gradle Plugin
    alias(libs.plugins.changelog) // Gradle Changelog Plugin
    alias(libs.plugins.qodana) // Gradle Qodana Plugin
}

group = properties("pluginGroup").get()
version = determineVersion()
val platformVersion = properties("platformVersion")
val platformType = properties("platformType")

// Set the JVM language level used to build the project.
kotlin {
    jvmToolchain(17)
}

repositories {
    mavenCentral()

    // IntelliJ Platform Gradle Plugin Repositories Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-repositories-extension.html
    intellijPlatform {
        defaultRepositories()
    }
}

// Dependencies are managed with Gradle version catalog - read more: https://docs.gradle.org/current/userguide/platforms.html#sub:version-catalog
dependencies {
    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
//        intellijIdeaCommunity("2022.3")
        create(platformType, platformVersion)

        val parser = { it: String -> it.split(',').map(String::trim).filter(String::isNotEmpty) }
        // Plugin Dependencies. Uses `platformBundledPlugins` property from the gradle.properties file for bundled IntelliJ Platform plugins.
        bundledPlugins(properties("platformBundledPlugins").map(parser))

        // Plugin Dependencies. Uses `platformPlugins` property from the gradle.properties file for plugin from JetBrains Marketplace.
        plugins(properties("platformPlugins").map(parser))

//        instrumentationTools()
        pluginVerifier()
    }
}

// Configure IntelliJ Platform Gradle Plugin - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-extension.html
intellijPlatform {
    projectName = project.name

    pluginConfiguration {
        name = properties("pluginName")
//        version = project.version

        // Extract the <!-- Plugin description --> section from README.md and provide for the plugin's manifest
        description = providers.fileContents(layout.projectDirectory.file("README.md")).asText.map {
            val start = "<!-- Plugin description -->"
            val end = "<!-- Plugin description end -->"

            with(it.lines()) {
                if (!containsAll(listOf(start, end))) {
                    throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
                }
                subList(indexOf(start) + 1, indexOf(end)).joinToString("\n").let(::markdownToHTML)
            }
        }

//        val changelog = project.changelog // local variable for configuration cache compatibility
//        // Get the latest available change notes from the changelog file
//        changeNotes = properties("pluginVersion").map { pluginVersion ->
//            with(changelog) {
//                val aVersion = pluginVersion.split('.')
//                val sVersion = aVersion.subList(0, 2).joinToString(".")
//                logger.info("Try to get changes for verions $sVersion")
//                renderItem(
//                    (getOrNull(sVersion) ?: getUnreleased())
//                        .withHeader(false)
//                        .withEmptySections(false),
//                    Changelog.OutputType.HTML,
//                )
//            }
//        }

        ideaVersion {
            sinceBuild = properties("pluginSinceBuild")
            untilBuild = properties("pluginUntilBuild")
        }
    }

    signing {
        certificateChain = environment("CERTIFICATE_CHAIN")
        privateKey = environment("PRIVATE_KEY")
        password = environment("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = environment("PUBLISH_TOKEN")
        // The pluginVersion is based on the SemVer (https://semver.org) and supports pre-release labels, like 2.1.7-alpha.3
        // Specify pre-release label to publish the plugin in a custom Release Channel automatically. Read more:
        // https://plugins.jetbrains.com/docs/intellij/deployment.html#specifying-a-release-channel
        channels = properties("pluginVersion").map {
            listOf(
                it.substringAfter('-', "").substringBefore('.').ifEmpty { "default" })
        }
    }

    pluginVerification {
        ides {
//            ide(IntelliJPlatformType.IntellijIdeaCommunity,"2022.3")
//            ide(IntelliJPlatformType.IntellijIdeaCommunity,platformVersion.get())
//            ide(IntelliJPlatformType.AndroidStudio,"2024.2.1.9")
            recommended()
        }
    }
}

// Configure Gradle Changelog Plugin - read more: https://github.com/JetBrains/gradle-changelog-plugin
changelog {
    groups.empty()
    repositoryUrl.set(properties("pluginRepositoryUrl"))
    version.set(project.version.toString().split('.').subList(0, 2).joinToString("."))
}


tasks {
    wrapper {
        gradleVersion = properties("gradleVersion").get()
    }

    buildSearchableOptions {
        enabled = false
    }
    jarSearchableOptions {
        enabled = false
    }

    publishPlugin {
        dependsOn(patchChangelog)
    }
}


intellijPlatformTesting {
    runIde {
        register("runIdeForUiTests") {
            task {
                jvmArgumentProviders += CommandLineArgumentProvider {
                    listOf(
                        "-Drobot-server.port=8082",
                        "-Dide.mac.message.dialogs.as.sheets=false",
                        "-Djb.privacy.policy.text=<!--999.999-->",
                        "-Djb.consents.confirmation.enabled=false",
                    )
                }
            }

            plugins {
                robotServerPlugin()
            }
        }
    }
}