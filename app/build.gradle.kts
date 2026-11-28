import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    // Renders the dashboard screens to PNG on the JVM (no emulator): ./gradlew :app:recordPaparazziSideloadDebug
    id("app.cash.paparazzi")
}

// Upload key for Google Play. Never committed: it lives in keystore.properties (git-ignored)
// next to the .jks file, or in the VLINK_* environment variables. See docs/play-store/RELEASE.md.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(key: String, env: String): String? = keystoreProps.getProperty(key) ?: System.getenv(env)
val uploadStoreFile = signingValue("storeFile", "VLINK_UPLOAD_STORE_FILE")

android {
    namespace = "com.dreyev.vlink"
    compileSdk = 36

    defaultConfig {
        // Final Play package name. It can never change once the app is on Play.
        applicationId = "com.dreyev.vlink"
        minSdk = 26
        // Google Play requires API 36 for new apps and updates since Aug 31, 2026.
        targetSdk = 36
        // Bump versionCode by 1 for every upload to Play; versionName is what users see.
        versionCode = 1
        versionName = "1.0.0"

        // Google Maps key: put MAPS_API_KEY=... in local.properties (gitignored) or the
        // MAPS_API_KEY environment variable. Never commit it. Without a key the app falls
        // back to the offline breadcrumb trail.
        val mapsKey = (rootProject.file("local.properties").takeIf { it.exists() }
            ?.readLines()?.firstOrNull { it.startsWith("MAPS_API_KEY=") }?.substringAfter("=")?.trim()
            ?: System.getenv("MAPS_API_KEY") ?: "").trim()
        manifestPlaceholders["MAPS_API_KEY"] = mapsKey
        buildConfigField("boolean", "HAS_MAPS_KEY", if (mapsKey.isNotEmpty()) "true" else "false")
    }

    signingConfigs {
        if (uploadStoreFile != null) {
            create("upload") {
                storeFile = rootProject.file(uploadStoreFile)
                storePassword = signingValue("storePassword", "VLINK_UPLOAD_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "VLINK_UPLOAD_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "VLINK_UPLOAD_KEY_PASSWORD")
            }
        }
    }

    flavorDimensions += "store"
    productFlavors {
        // Google Play build: phone app only. Android Auto is left out (see app/src/sideload).
        create("play") {
            dimension = "store"
        }
        // Personal build with the Android Auto module, installed by hand (AA developer mode).
        // Different package so it can sit next to the Play install.
        create("sideload") {
            dimension = "store"
            applicationIdSuffix = ".sideload"
            versionNameSuffix = "-aa"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the upload key when one is configured; otherwise the release output is
            // unsigned and Play will refuse it (debug builds are unaffected).
            signingConfig = signingConfigs.findByName("upload")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
}

dependencies {
    implementation(project(":obd-core"))

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    "sideloadImplementation"("androidx.car.app:app:1.7.0")
    // Google Maps (phone map tab, GPS card and Android Auto map page)
    implementation("com.google.android.gms:play-services-maps:19.2.0")
    implementation("com.google.maps.android:maps-compose:6.4.1")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}
