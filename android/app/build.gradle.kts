import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Personal settings that stay out of git, in android/local.properties:
//   reddot.serverUrl=http://100.x.y.z:8090    the server a fresh install starts with (changeable in the app)
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
// Release signing, also out of git, in android/keystore.properties:
//   storeFile=/home/you/.config/reddot/reddot-release.jks
//   storePassword=...   keyAlias=reddot   keyPassword=...
// Without it, release builds are signed with the debug key. Keep using the same key: Android
// only installs an update over an app signed with the same key (otherwise: uninstall first,
// which deletes the notes on the phone).
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "io.github.kdiep12.reddot"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.kdiep12.reddot"
        minSdk = 29          // Android 10
        targetSdk = 36       // Android 16: what Google Play requires of new apps and updates since 2026-08-31
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "DEFAULT_SERVER_URL", "\"${localProps.getProperty("reddot.serverUrl", "")}\"")
    }

    signingConfigs {
        keystoreProps.getProperty("storeFile")?.let { path ->
            create("release") {
                storeFile = file(path)
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
