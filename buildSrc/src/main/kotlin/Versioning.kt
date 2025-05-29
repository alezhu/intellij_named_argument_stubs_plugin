import org.gradle.api.Project
import java.io.File
import java.util.*

object Versioning {
    private const val VERSION_KEY = "pluginVersion"
    private val versionRegex = Regex("""^\s*$VERSION_KEY\s*=\s*(\d+\.\d+\.\d+\.\d+)\s*$""")
    private var versionFile: File? = null

    private fun readVersion(): String {
        val props = Properties().apply {
            versionFile!!.inputStream().use { load(it) }
        }
        return props.getProperty(VERSION_KEY) ?: error("$VERSION_KEY not found")
    }

    private fun incrementVersion(version: String): String {
        val parts = version.split(".").toMutableList()
        if (parts.size != 4) error("Version format must be A.B.C.D")
        parts[3] = ((parts[3].toIntOrNull() ?: error("Invalid build number")) + 1).toString()
        return parts.joinToString(".")
    }

    private fun incrementVersionInFile(): String {
        val lines = versionFile!!.readLines().toMutableList()
        val index = lines.indexOfFirst { versionRegex.matches(it) }
        if (index == -1) error("No $VERSION_KEY entry found")

        val oldVersion = versionRegex.find(lines[index])!!.groupValues[1]
        val newVersion = incrementVersion(oldVersion)

        lines[index] = "$VERSION_KEY=$newVersion"
        versionFile!!.writeText(lines.joinToString(System.lineSeparator()))

        println(">> Version incremented: $oldVersion -> $newVersion")
        return newVersion
    }

    private fun determineVersion(taskNames: List<String>): String {
        val isReleaseBuild = taskNames.any {
            it in listOf("build", "buildPlugin", "patchPluginXml")
        }
        return if (isReleaseBuild) incrementVersionInFile() else readVersion()
    }

    fun Project.determineVersion(): String {
        if (versionFile == null) {
            versionFile = this.rootProject.file("gradle.properties").absoluteFile
        }
        return determineVersion(this.gradle.startParameter.taskNames)
    }
}