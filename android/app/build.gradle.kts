import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing uses the shared upload key (see the mobile release
// runbook). android/key.properties holds storeFile, storePassword, keyAlias
// and keyPassword; without it, local release builds fall back to debug
// signing (CI builds are left unsigned instead).
val signing = rootProject.file("key.properties").takeIf { it.exists() }?.let { f ->
    Properties().apply { f.inputStream().use(::load) }
}

// `local.properties` beside settings.gradle.kts is gitignored, so a machine's
// own values (the SDK path, a Google client id) can live there.
val localProperties = rootProject.file("local.properties").takeIf { it.exists() }?.let { f ->
    Properties().apply { f.inputStream().use(::load) }
} ?: Properties()

// The Google Cloud *Web* OAuth client id that Sign in with Google asks for an
// ID token for (Supabase checks the token's audience against it). Empty hides
// the Google button. The first of these that is set wins: the Gradle property
// (`-PlibrelogGoogleWebClientId=…` or ~/.gradle/gradle.properties), the
// LIBRELOG_GOOGLE_WEB_CLIENT_ID environment variable (CI), then
// `librelogGoogleWebClientId=…` in local.properties. docs/google-sign-in.md
// has the console steps that make one.
fun String?.nonBlank() = this?.trim()?.takeIf { it.isNotEmpty() }
val googleWebClientId = (findProperty("librelogGoogleWebClientId") as String?).nonBlank()
    ?: System.getenv("LIBRELOG_GOOGLE_WEB_CLIENT_ID").nonBlank()
    ?: localProperties.getProperty("librelogGoogleWebClientId").nonBlank()
    ?: ""

android {
    namespace = "uk.co.maybeitssoftware.librelog"
    // Current AndroidX, Compose and OkHttp releases require 37.2.
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "uk.co.maybeitssoftware.librelog"
        // scripts/build-android.sh builds the core for this API level too.
        minSdk = 28
        targetSdk = 36
        // Each upload to Play needs a higher code: pass -PversionCode=N.
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("versionName") as String?) ?: "0.1.0"
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"$googleWebClientId\"")
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    signingConfigs {
        if (signing != null) {
            create("upload") {
                storeFile = file(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // In CI (CI=true) a missing key.properties leaves the bundle
            // unsigned rather than debug-signed, so it can't reach Play by
            // accident; local builds still fall back to the debug key.
            signingConfig = signingConfigs.findByName("upload")
                ?: if (providers.environmentVariable("CI").orNull == "true") null
                else signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        // JNA's desktop natives come in with the test jar; the app only needs
        // the Android ones from the AAR.
        resources.excludes += listOf("com/sun/jna/**/*.dylib", "com/sun/jna/**/*.dll", "com/sun/jna/**/*.so", "META-INF/*.md")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // JVM tests call the real core through JNA, built for the host
                // by scripts/build-android.sh.
                it.systemProperty("jna.library.path", rootProject.file("../core/target/release").absolutePath)
            }
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.process)
    implementation(libs.core.ktx)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.okhttp)
    implementation("${libs.jna.get()}@aar")
    implementation(libs.work.runtime)
    implementation(libs.security.crypto)
    implementation(libs.zxing.core)
    implementation(libs.code.scanner)
    implementation(libs.credentials)
    implementation(libs.credentials.play.services)
    implementation(libs.googleid)
    implementation(libs.exifinterface)
    implementation(libs.billing)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.jna)
}
