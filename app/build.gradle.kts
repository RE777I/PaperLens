import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Release signing comes from local.properties (never committed) or environment variables.
// See README "Release build". Without them the release build falls back to unsigned APKs.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun signingValue(prop: String, env: String): String? =
    localProps.getProperty(prop) ?: System.getenv(env)

android {
    namespace = "com.paperlens.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.paperlens.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        val storePath = signingValue("release.storeFile", "PAPERLENS_STORE_FILE")
        if (storePath != null) {
            create("release") {
                storeFile = rootProject.file(storePath)
                storePassword = signingValue("release.storePassword", "PAPERLENS_STORE_PASSWORD")
                keyAlias = signingValue("release.keyAlias", "PAPERLENS_KEY_ALIAS")
                keyPassword = signingValue("release.keyPassword", "PAPERLENS_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    // One APK per ABI: the ML Kit translate/OCR/language-id native libraries are ~30 MB per ABI.
    // App bundles (bundleRelease) split by ABI on their own, so this only affects assembleRelease.
    // The corner model is memory-mapped from the APK copy, so keep it uncompressed.
    androidResources {
        noCompress += "onnx"
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
            // BouncyCastle post-quantum parameter tables (~8 MB). PdfBox only uses classic RC4/AES/RSA.
            "org/bouncycastle/pqc/**",
        )
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.fragment:fragment-ktx:1.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.navigation:navigation-compose:2.8.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.print:print:1.0.0")

    // Room (documents, pages, QR history)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Image loading
    implementation("io.coil-kt:coil-compose:2.7.0")

    // In-app camera (capture screen with modes); the ML Kit scanner remains available in settings
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    // Google ML Kit: document scanner (edge detection, crop, cleaning), OCR, translation, barcodes
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0-beta1")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    // Reading codes from an existing photo (the code scanner above only works with the camera)
    implementation("com.google.android.gms:play-services-mlkit-barcode-scanning:18.3.1")
    // Learned page-corner detection (DocAligner model in assets/)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    // Latin OCR is bundled so it works offline from the first launch. The other scripts use the
    // Play services variants: their models download on demand instead of adding ~4 MB each to the APK.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition-chinese:16.0.1")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition-japanese:16.0.1")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition-korean:16.0.1")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition-devanagari:16.0.1")
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // PDF writing with JPEG pass-through, compression and password protection
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
}
