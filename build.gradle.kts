import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

fun properties(key: String) = providers.gradleProperty(key)
fun environment(key: String) = providers.environmentVariable(key)

plugins {
    id("java") // Java support
    alias(libs.plugins.kotlin) // Kotlin support
    alias(libs.plugins.intelliJPlatform) // IntelliJ Platform Gradle Plugin
    alias(libs.plugins.changelog) // Gradle Changelog Plugin

    alias(libs.plugins.libVersionsUpdater)

}

group = properties("pluginGroup").get()
version = properties("pluginVersion").get()
val platformVersion = properties("platformVersion")

// Set the JVM language level used to build the project.
kotlin {
    jvmToolchain(17)

    // `manual/` is a scratch pad for trying the intention by hand, so it is allowed to hold
    // non-compiling calls. It stays a directory of the main Kotlin source set — that is what lets
    // the IDE resolve the calls in it — but is filtered out before compilation and the plugin jar.
    sourceSets.named("main") {
        kotlin {
            srcDir("manual")
            exclude("**/demo.kt")
        }
    }
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
    testImplementation(libs.junit)

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        // Since 2025.3 (253) IDEA Community and Ultimate are modes of one distribution, so the
        // build targets that single artifact instead of the `IC`/`IU` product codes.
        intellijIdea(platformVersion)

        val parser = { it: String -> it.split(',').map(String::trim).filter(String::isNotEmpty) }
        // Plugin Dependencies. Uses `platformBundledPlugins` property from the gradle.properties file for bundled IntelliJ Platform plugins.
        bundledPlugins(properties("platformBundledPlugins").map(parser))

        // Plugin Dependencies. Uses `platformPlugins` property from the gradle.properties file for plugin from JetBrains Marketplace.
        plugins(properties("platformPlugins").map(parser))

        pluginVerifier()

        testBundledPlugins(properties("platformTestBundledPlugins").map(parser))
        testFramework(TestFrameworkType.Platform)
    }
}

// Configure IntelliJ Platform Gradle Plugin - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-extension.html
intellijPlatform {
    projectName = project.name

    pluginConfiguration {
        name = properties("pluginName")

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
            // `recommended()` walks the whole sinceBuild..untilBuild range and resolves 9 IDE builds
            // (~12 GB, over an hour). These three cover the guard we actually need: the floor, the last
            // real Community artifact, and the target — and Community builds are what catches an
            // Ultimate-only API reference, since the compile classpath can no longer exclude it.
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2023.3")
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2025.2.5")
            create(IntelliJPlatformType.IntellijIdea, "2026.2.4")
        }
    }

}

// Configure Gradle Changelog Plugin - read more: https://github.com/JetBrains/gradle-changelog-plugin
changelog {
    versionPrefix.set("")
    repositoryUrl.set(properties("pluginRepositoryUrl"))
    // changelog and CI both address the release by its first three version segments
    version.set(properties("pluginVersion").get().split('.').subList(0, 3).joinToString("."))
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

    patchPluginXml {
        changeNotes = provider {
            changelog.getAll()
                .filter { !it.value.isUnreleased }
                .map {
                    changelog.renderItem(it.value, Changelog.OutputType.HTML)
                }.joinToString()
        }
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