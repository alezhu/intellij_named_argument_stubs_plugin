import org.jetbrains.intellij.IntelliJPluginConstants

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.7.22"
    id("org.jetbrains.intellij") version "1.11.0"
}

group = "ru.alezhu.idea.plugins.named_argument_stubs"
version = "1.0.2.34"

repositories {
    mavenCentral()
}

dependencies {
//    implementation(kotlin("reflect"))
}

// Configure Gradle IntelliJ Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-gradle-intellij-plugin.html
intellij {
    version.set("2021.3.3")
    type.set("IC") // Target IDE Platform

    plugins.set(listOf("java", "Kotlin"))
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "11"
        targetCompatibility = "11"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "11"
    }

    patchPluginXml {
        version.set("${project.version}")
        sinceBuild.set("213")
        untilBuild.set("242.*")
    }

    buildSearchableOptions {
        enabled = false
    }
    jarSearchableOptions {
        enabled = false
    }
    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
    }

    runIde {
        // Absolute path to installed target 3.5 Android Studio to use as
        // IDE Development Instance (the "Contents" directory is macOS specific):
        ideDir.set(file("C:\\Program Files\\Android\\Android Studio"))
    }
    create("incrementBuild") {
        group = "version"
        description = "Increment build number in version on each build"
        doFirst {
            if (!buildFile.canWrite()) {
                println("Can't write new version to file")
                return@doFirst
            }
            val versionParts = version.toString().split(".").map(String::toInt).toMutableList()
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
                        pattern = """version\s*=\s*"$version"""",
                        option = RegexOption.MULTILINE,
                    ),
                    replacement = "version = \"$newVersion\"",
                )
            println("Version: $version -> $newVersion")

            buildFile.writeText(newContent)
            version = newVersion
            project.version = newVersion
            named(IntelliJPluginConstants.PATCH_PLUGIN_XML_TASK_NAME) {
                this.setProperty("version", newVersion)
            }
        }
    }
    named(IntelliJPluginConstants.BUILD_PLUGIN_TASK_NAME) {
        dependsOn(":incrementBuild")
    }
    named(IntelliJPluginConstants.PATCH_PLUGIN_XML_TASK_NAME) {
        dependsOn(":incrementBuild")
    }

}
