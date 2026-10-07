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
