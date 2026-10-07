import java.util.Properties

plugins {
    alias(libs.plugins.android.application) apply false
}

val localBuildProperties = Properties().apply {
    val configFile = rootProject.file("local.properties")
    if (configFile.exists()) configFile.inputStream().use { load(it) }
}
val localBuildOutput = localBuildProperties.getProperty("BUILD_OUTPUT_DIR")
    ?.trim()?.takeIf { it.isNotEmpty() }

// IDE builds need the same escape from cloud-sync file locks as command-line builds.
if (localBuildOutput != null) {
    val outputRoot = rootProject.file(localBuildOutput).canonicalFile
    require(!rootProject.projectDir.canonicalFile.toPath().startsWith(outputRoot.toPath())) {
        "BUILD_OUTPUT_DIR must not be the project directory or one of its parents."
    }
    allprojects {
        layout.buildDirectory.set(outputRoot.resolve(name))
    }
}
