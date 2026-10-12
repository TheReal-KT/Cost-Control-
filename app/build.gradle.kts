import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val clientProperties = Properties().apply {
    val configFile = rootProject.file("local.properties")
    if (configFile.exists()) configFile.inputStream().use { load(it) }
}
fun clientConfig(name: String): String = providers.environmentVariable(name)
    .orElse(clientProperties.getProperty(name, "")).get()
fun javaString(value: String): String = "\"" + value.replace("\\", "\\\\")
    .replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""

val publishableKey = clientConfig("SUPABASE_PUBLISHABLE_KEY").trim()
require(publishableKey.isEmpty() || publishableKey.startsWith("sb_publishable_")) {
    "SUPABASE_PUBLISHABLE_KEY must be a modern public client key; secret keys must never enter the APK."
}

android {
    namespace = "com.example.agentcostcontrol"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.agentcostcontrol"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "SUPABASE_URL", javaString(clientConfig("SUPABASE_URL")))
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", javaString(publishableKey))
        buildConfigField("String", "AI_ENDPOINT_URL", javaString(clientConfig("AI_ENDPOINT_URL")))

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}

// A locally generated public certificate is used only by debug builds on loopback.
// The private key stays in ignored .local-demo and never enters Android resources.
val localCertificatePath = clientConfig("LOCAL_AI_CERT_FILE").trim()
if (localCertificatePath.isNotEmpty()) {
    val localCertificate = rootProject.file(localCertificatePath)
    require(localCertificate.isFile) { "Run scripts/Initialize-LocalDemo.ps1 to create LOCAL_AI_CERT_FILE." }
    androidComponents.onVariants(androidComponents.selector().withBuildType("debug")) { variant ->
        val generateLocalDemoResources = tasks.register<LocalDemoResources>("generateLocalDemoResources") {
            certificate.set(localCertificate)
            outputDirectory.set(layout.buildDirectory.dir("generated/localDemoResources"))
        }
        variant.sources.res?.addGeneratedSourceDirectory(generateLocalDemoResources, LocalDemoResources::outputDirectory)
    }
}

abstract class LocalDemoResources : DefaultTask() {
    @get:InputFile
    abstract val certificate: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val directory = outputDirectory.get().asFile
        val raw = directory.resolve("raw").apply { mkdirs() }
        certificate.get().asFile.copyTo(raw.resolve("local_demo_certificate.pem"), overwrite = true)
        val xml = directory.resolve("xml").apply { mkdirs() }
        xml.resolve("network_security_config.xml").writeText("""
            <network-security-config>
                <base-config cleartextTrafficPermitted="false">
                    <trust-anchors><certificates src="system" /></trust-anchors>
                </base-config>
                <domain-config cleartextTrafficPermitted="false">
                    <domain>127.0.0.1</domain>
                    <domain>localhost</domain>
                    <trust-anchors><certificates src="@raw/local_demo_certificate" /></trust-anchors>
                </domain-config>
            </network-security-config>
        """.trimIndent())
    }
}
