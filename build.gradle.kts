import org.jetbrains.intellij.platform.gradle.Constants
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

fun properties(key: String) = providers.gradleProperty(key)
fun environment(key: String) = providers.environmentVariable(key)

plugins {
    id("java")
//    id("org.jetbrains.intellij.platform.migration") version "2.1.0"
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.gradleIntelliJPlugin) // Gradle IntelliJ Plugin
}

group = properties("pluginGroup").get()
version = "1.0.4.104"
val jvmVersion = "17"
val platformVersion = properties("platformVersion")
val platformType = properties("platformType")

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// Set the JVM language level used to build the project.
kotlin {
    jvmToolchain(17)
}

dependencies {

    intellijPlatform {
//        intellijIdeaCommunity("2022.3")
        create(platformType,platformVersion)

        val parser = { it: String -> it.split(',').map(String::trim).filter(String::isNotEmpty) }
        plugins(providers.gradleProperty("platformPlugins").map(parser))
        bundledPlugins(providers.gradleProperty("platformBundledPlugins").map(parser))

        instrumentationTools()
        pluginVerifier()
    }
}

// Configure Gradle IntelliJ Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-gradle-intellij-plugin.html
intellijPlatform {
    projectName = project.name

    pluginConfiguration {
        version.set("${project.version}")
        ideaVersion {
            sinceBuild = properties("pluginSinceBuild")
            untilBuild = properties("pluginUntilBuild")
        }
    }

    signing {
        certificateChain = System.getenv("CERTIFICATE_CHAIN")
        privateKey = System.getenv("PRIVATE_KEY")
        password = System.getenv("PRIVATE_KEY_PASSWORD")
    }


    publishing {
        token = System.getenv("PUBLISH_TOKEN")
    }

    pluginVerification {
        ides {
            ide(IntelliJPlatformType.IntellijIdeaCommunity,"2022.3")
//            recommended()
        }
    }
}


tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = jvmVersion
        targetCompatibility = jvmVersion
    }
    withType<KotlinCompile> {
        compilerOptions {
            jvmTarget.set(JvmTarget.fromTarget(jvmVersion))
        }
    }


    buildSearchableOptions {
        enabled = false
    }
    jarSearchableOptions {
        enabled = false
    }


//    runIde {
//        // Absolute path to installed target 3.5 Android Studio to use as
//        // IDE Development Instance (the "Contents" directory is macOS specific):
////        ideDir.set(file("C:\\Program Files\\Android\\Android Studio"))
//    }

    create("incrementBuild") {
        group = "version"
        description = "Increment build number in version on each build"
        val versionStr = version.toString()
        val buildFile = project.buildFile

        doFirst {
            if (!buildFile.canWrite()) {
                println("Can't write new version to file")
                return@doFirst
            }
            val versionParts = versionStr.split(".").map(String::toInt).toMutableList()
            //0 - major
            //1 - minor
            //2 - patch
            //3 - build
            if (versionParts.size < 4) {
                return@doFirst
            }
            versionParts[3]++
            val newVersion = versionParts.joinToString(".")
            val content = buildFile.readText()
            val newContent =
                content.replace(
                    regex = Regex(
                        pattern = """version\s*=\s*"$versionStr"""",
                        option = RegexOption.MULTILINE,
                    ),
                    replacement = "version = \"$newVersion\"",
                )
            println("Version: $versionStr -> $newVersion")

            buildFile.writeText(newContent)
//            version = newVersion
//            project.version = newVersion
//            named(Constants.Tasks.PATCH_PLUGIN_XML) {
//                    for (propName in listOf("version","pluginVersion")) {
//                        if (this.hasProperty(propName)) this.setProperty(propName, newVersion)
//                }
//            }
        }
    }
    named(Constants.Tasks.BUILD_PLUGIN) {
        dependsOn(":incrementBuild")
    }
    named(Constants.Tasks.PATCH_PLUGIN_XML) {
        dependsOn(":incrementBuild")
    }

}
