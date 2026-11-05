import org.gradle.api.logging.Logging
import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

fun properties(key: String) = providers.gradleProperty(key)
fun environment(key: String) = providers.environmentVariable(key)

// Newest Android Studio build of each feed channel, from the official releases feed. One result
// carries both channels, and a ValueSource is obtained once per provider instance, so the verifier
// matrix and its runIde twins share a single download of the feed instead of one per call site.
// An unreachable feed, or a channel missing from it, falls back to the pinned build below, so an
// offline build still configures instead of failing before `--offline` even applies.
abstract class AndroidStudioVersions : ValueSource<Map<String, String>, AndroidStudioVersions.Params> {
    interface Params : ValueSourceParameters {
        val releaseFallback: Property<String>
        val canaryFallback: Property<String>
    }

    override fun obtain(): Map<String, String> {
        val fetched = try {
            newestVersions(fetchFeed())
        } catch (e: IOException) {
            logger.warn("Android Studio releases feed $FEED unavailable, pinned builds are used: $e")
            emptyMap()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.warn("Android Studio releases feed request interrupted, pinned builds are used")
            emptyMap()
        }
        val fallback = mapOf(
            "Release" to parameters.releaseFallback.get(),
            "Canary" to parameters.canaryFallback.get(),
        )
        return fallback.mapValues { (channel, pinned) ->
            fetched[channel] ?: pinned.also {
                if (fetched.isNotEmpty()) {
                    logger.warn("No '$channel' Android Studio build in the feed, pinned $pinned is used")
                }
            }
        }
    }

    private fun fetchFeed(): String {
        val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
        val request = HttpRequest.newBuilder(URI.create(FEED))
            .timeout(Duration.ofMinutes(2))
            .GET()
            .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString()).body()
    }

    // The feed is newest-first, so the first item of a channel is its newest build.
    private fun newestVersions(xml: String): Map<String, String> = CHANNELS.mapNotNull { channel ->
        val marker = Regex("<channel>\\s*${Regex.escape(channel)}\\s*</channel>")
        val item = ITEM.findAll(xml).map { it.value }.firstOrNull { marker.containsMatchIn(it) }
            ?: return@mapNotNull null
        VERSION.find(item)?.groupValues?.get(1)?.trim()?.let { channel to it }
    }.toMap()

    private val logger = Logging.getLogger(AndroidStudioVersions::class.java)

    companion object {
        private const val FEED = "https://jb.gg/android-studio-releases-list.xml"
        private val CHANNELS = listOf("Release", "Canary")
        private val ITEM = Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL)
        private val VERSION = Regex("<version>([^<]+)</version>")
    }
}

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

// The pinned builds are only a fallback for an unreachable feed, never the normal source: refresh
// them from the feed when the verifier reports that one of them no longer resolves.
val androidStudioVersions = providers.of(AndroidStudioVersions::class.java) {
    parameters.releaseFallback.set("2026.2.2.8")
    parameters.canaryFallback.set("2026.2.3.2")
}
val androidStudioRelease = androidStudioVersions.map { it.getValue("Release") }
val androidStudioCanary = androidStudioVersions.map { it.getValue("Canary") }

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
            // until-build stays unset: newer IDEs install the plugin by default and `verifyPlugin`
            // against the IDE matrix below is what proves that claim before a release.
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
            // `recommended()` walks the whole sinceBuild range and resolves 9 IDE builds
            // (~12 GB, over an hour). These are the guard we actually need: the floor, the last
            // real Community artifact, and the target — Community builds catch an Ultimate-only API
            // reference, since the compile classpath can no longer exclude it. Android Studio joins
            // as the third-party host whose bundled Kotlin plugin most often diverges from the IDE's;
            // its versions follow the official feed (https://jb.gg/android-studio-releases-list.xml),
            // so newest stable and newest canary need no re-pinning; only the offline fallback is
            // pinned.
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2023.3")
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2025.2.5")
            create(IntelliJPlatformType.IntellijIdea, "2026.2.4")
            create(IntelliJPlatformType.AndroidStudio, androidStudioRelease)
            create(IntelliJPlatformType.AndroidStudio, androidStudioCanary)
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
        // Only the section being released: the full archive would grow plugin.xml on every release,
        // and Marketplace/IDE show these notes as "What's new" for the current update only.
        changeNotes = provider {
            val item = changelog.getAll().values
                .firstOrNull {
                    it.isUnreleased && (it.summary.isNotEmpty() || it.sections.any { (_, v) -> v.isNotEmpty() })
                }
                ?: changelog.getLatest()
            changelog.renderItem(item, Changelog.OutputType.HTML)
        }
    }

    // The enabled-plugins list decides which platform modules the test runtime boots, but Gradle does
    // not see it as an input, so changing the list replayed a cached pass for the old one. Declaring
    // the property keeps the test result honest both locally and in CI.
    test {
        inputs.property("platformTestBundledPlugins", properties("platformTestBundledPlugins"))
    }

    named("prepareTest") {
        inputs.property("platformTestBundledPlugins", properties("platformTestBundledPlugins"))
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

        // One sandbox IDE per `pluginVerification.ides` entry: `type`/`version` override the main
        // platform dependency only for that task, so the same plugin zip can be smoke-tested
        // behaviorally where the verifier only checked it statically. AS tasks download the
        // installer (~1.5 GB) on first run.
        fun onMatrixIde(taskName: String, ideType: IntelliJPlatformType, ideVersion: Any) {
            register(taskName) {
                type = ideType
                when (ideVersion) {
                    is String -> version = ideVersion
                    is Provider<*> -> version = ideVersion.map { it.toString() }
                }
            }
        }

        onMatrixIde("runIdeOnIdea2023", IntelliJPlatformType.IntellijIdeaCommunity, "2023.3")
        onMatrixIde("runIdeOnIdeaCommunity2025", IntelliJPlatformType.IntellijIdeaCommunity, "2025.2.5")
        onMatrixIde("runIdeOnIdeaLatest", IntelliJPlatformType.IntellijIdea, "2026.2.4")
        onMatrixIde("runIdeOnAndroidStudio", IntelliJPlatformType.AndroidStudio, androidStudioRelease)
        onMatrixIde("runIdeOnAndroidStudioCanary", IntelliJPlatformType.AndroidStudio, androidStudioCanary)

        // Runs the developer's installed IDE, so pre-release builds that are not on the installer
        // CDN can be tried without a second download.
        register("runIdeOnLocalIdea") {
            localPath.set(file("C:/Users/alezhur/AppData/Local/Programs/IntelliJ IDEA"))
        }
    }
}
