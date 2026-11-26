plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.nada.music"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nada.music"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            // HP Android modern (arm64) + HP lama (armv7) + emulator. Membuang x86 32-bit memperkecil APK.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    // Kunci tanda tangan tetap, supaya setiap APK baru bisa dipasang menimpa versi lama tanpa
    // menghapus data. Di GitHub Actions kuncinya diambil dari Secrets (lihat .github/workflows/build.yml).
    // Kalau tidak ada (mis. build di komputer sendiri), dipakai debug key.
    val releaseKeystore = System.getenv("SIGNING_KEYSTORE_PATH")?.let { file(it) }?.takeIf { it.isFile }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (releaseKeystore != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // NewPipeExtractor memakai API Java (java.time, java.nio) yang perlu desugaring.
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")

    val room = "2.6.1"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.9.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.palette:palette-ktx:1.0.0")
    // Efek kaca buram (backdrop blur) ala iOS. Blur aktif di Android 12+, versi lama memakai kaca transparan.
    implementation("dev.chrisbanes.haze:haze:1.1.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Ekstraksi YouTube / YouTube Music (pencarian, URL stream, lagu terkait).
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.27.1")

    // Jarvis: wake word "Jarvis" (keyword spotting) dan suara Kokoro, offline di HP.
    // AAR resmi sherpa-onnx v1.12.15 (onnxruntime ditautkan statis), dari
    // https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.12.15
    implementation(files("libs/sherpa-onnx-static-link-onnxruntime-1.12.15.aar"))
    // Mengekstrak model Kokoro (.tar.bz2) yang diunduh saat Jarvis pertama diaktifkan.
    implementation("org.apache.commons:commons-compress:1.27.1")

    // Remote PC: server HTTP kecil (tanpa dependensi lain) untuk mengatur musik dari browser.
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    testImplementation("junit:junit:4.13.2")
}
