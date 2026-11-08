import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// sherpa-onnx (Apache-2.0) runs Parakeet on the phone. Its AAR is 49 MB, so
// it's fetched from the upstream release instead of living in git.
val sherpaVersion = "2.0.1"
val sherpaSha256 = "a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7"
val sherpaAar = file("libs/sherpa-onnx-$sherpaVersion.aar")

fun sha256(f: File): String = MessageDigest.getInstance("SHA-256")
    .digest(f.readBytes()).joinToString("") { "%02x".format(it) }

if (!sherpaAar.exists() || sha256(sherpaAar) != sherpaSha256) {
    sherpaAar.parentFile.mkdirs()
    val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/sherpa-onnx-$sherpaVersion.aar"
    logger.lifecycle("Downloading $url")
    URI(url).toURL().openStream().use { input -> sherpaAar.outputStream().use { input.copyTo(it) } }
    check(sha256(sherpaAar) == sherpaSha256) { "sherpa-onnx AAR checksum mismatch" }
}

android {
    namespace = "ca.heeney.squelch"
    compileSdk = 35

    defaultConfig {
        applicationId = "ca.heeney.squelch"
        minSdk = 30
        targetSdk = 35
        versionCode = System.getenv("SQUELCH_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("SQUELCH_VERSION_NAME") ?: "0.1.0"
        ndk {
            abiFilters += "arm64-v8a"
            // -Pemu for testing on an x86_64 emulator
            if (project.hasProperty("emu")) abiFilters += "x86_64"
        }
    }

    signingConfigs {
        val ksPath = System.getenv("SQUELCH_KEYSTORE_PATH")
        if (ksPath != null) {
            create("release") {
                storeFile = file(ksPath)
                storePassword = System.getenv("SQUELCH_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SQUELCH_KEY_ALIAS") ?: "squelch"
                keyPassword = System.getenv("SQUELCH_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        // With the release key around, debug builds use it too, so a debug install
        // (model copied in over adb) can be upgraded in place by a release.
        val key = signingConfigs.findByName("release")
        release {
            isMinifyEnabled = false
            signingConfig = key ?: signingConfigs.getByName("debug")
        }
        debug {
            if (key != null) signingConfig = key
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs { useLegacyPackaging = false } }
}

dependencies {
    implementation(files(sherpaAar))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
}
