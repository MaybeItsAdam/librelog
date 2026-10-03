plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "uk.co.maybeitsadam.dailylog"
    // Current AndroidX, Compose and OkHttp releases require 37.2.
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "uk.co.maybeitsadam.dailylog"
        // scripts/build-android.sh builds the core for this API level too.
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

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

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.jna)
}
