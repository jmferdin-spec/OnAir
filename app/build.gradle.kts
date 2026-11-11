import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Optional developer API keys from "secrets.properties" (ignored by Git) or environment variables.
// Normal users enter their own keys in the app (Settings > API keys); without keys the build contains none.
val secrets = Properties().apply {
    val f = rootProject.file("secrets.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(name: String): String =
    (secrets.getProperty(name) ?: System.getenv(name) ?: "").trim().filter { it.isLetterOrDigit() }

// OnAir release signing: keystore and passwords live OUTSIDE the repo (see CLAUDE.md, "Release build").
// Default file: <user home>/.onair/signing.properties (storeFile, storePassword, keyAlias, keyPassword);
// another location via -Ponair.signing=<path> or the ONAIR_SIGNING environment variable.
// Without the file the release build still builds, just unsigned.
val signingFile = ((findProperty("onair.signing") as String?) ?: System.getenv("ONAIR_SIGNING"))?.let { file(it) }
    ?: File(System.getProperty("user.home"), ".onair/signing.properties")
val signing = Properties().apply { if (signingFile.exists()) signingFile.inputStream().use { load(it) } }

android {
    namespace = "com.ipvio.stream"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.onair.tv"   // OnAir: installs next to the original IPVIO; Kotlin package stays com.ipvio.stream
        minSdk = 24
        targetSdk = 37
        versionCode = 2
        versionName = "2.0"

        buildConfigField("String", "TMDB_KEY", "\"${secret("TMDB_KEY")}\"")
        buildConfigField("String", "OMDB_KEY", "\"${secret("OMDB_KEY")}\"")
    }

    signingConfigs {
        if (signing.getProperty("storeFile") != null) create("onair") {
            storeFile = file(signing.getProperty("storeFile"))
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("onair")?.let { signingConfig = it }
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
                // OnAir: keeps R8 from moving classes out of their packages (release crashed at launch, see the file)
                keepRules { files.add(file("proguard-rules.pro")) }
            }
        }
        // OnAir: day-to-day TV testing – the release build without R8 (minutes faster). Same applicationId and release
        // signing, so it installs over the release build and keeps the login and data. Not for publishing: R8 problems only
        // show in the real release build (check that on the TV before tagging).
        create("staging") {
            initWith(getByName("release"))
            optimization { enable = false }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        disable += "UnsafeOptInUsageError"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")

    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    // Software audio decoder (AC3/E-AC3/DTS ...) for headphones/Bluetooth, see SoftAudio.kt. GPL-3.0 – the reason the app is GPL-3.0.
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.9.0+1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")

    implementation("androidx.work:work-runtime-ktx:2.10.0")

    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM unit tests (Android's copy is only a stub there); used by XtreamApiLogicTest
    testImplementation("org.json:json:20240303")
}

// OnAir: the release lint check ("lint vital", ~2.5 min) runs for release only, not for the staging test build
tasks.matching { it.name.startsWith("lintVital") && it.name.contains("Staging") }.configureEach { enabled = false }
