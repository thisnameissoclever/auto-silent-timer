import java.io.File
import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing credentials are read from a gitignored `keystore.properties` (local dev
// convenience), falling back to environment variables (other machines / CI) so that NO secret
// file needs to exist in the repo at all. See keystore.properties.example. If nothing is
// configured, release builds are left unsigned so the project still configures and builds.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        FileInputStream(keystorePropertiesFile).use { load(it) }
    }
}

// Local, non-synced fallback for the "encrypted on Drive" workflow: `npm run decrypt-keystore`
// writes the decrypted keystore + credentials here (outside the Drive-synced repo).
val localKeystorePropertiesFile =
    File(System.getProperty("user.home"), ".astimer-keystore${File.separator}keystore.properties")
val localKeystoreProperties = Properties().apply {
    if (localKeystorePropertiesFile.exists()) {
        FileInputStream(localKeystorePropertiesFile).use { load(it) }
    }
}

fun signingValue(propKey: String, envKey: String): String? =
    keystoreProperties.getProperty(propKey)?.takeIf { it.isNotBlank() }
        ?: System.getenv(envKey)?.takeIf { it.isNotBlank() }
        ?: localKeystoreProperties.getProperty(propKey)?.takeIf { it.isNotBlank() }

val releaseStoreFilePath = signingValue("storeFile", "ASTIMER_KEYSTORE_FILE")
val releaseStorePassword = signingValue("storePassword", "ASTIMER_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "ASTIMER_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "ASTIMER_KEY_PASSWORD")

val hasReleaseSigning = releaseStoreFilePath != null && releaseStorePassword != null &&
    releaseKeyAlias != null && releaseKeyPassword != null &&
    rootProject.file(releaseStoreFilePath).exists()

if (!hasReleaseSigning) {
    logger.lifecycle(
        "auto-silent-timer: release signing not configured -> release builds will be UNSIGNED. " +
            "Provide keystore.properties (see keystore.properties.example) or set the env vars " +
            "ASTIMER_KEYSTORE_FILE / ASTIMER_KEYSTORE_PASSWORD / ASTIMER_KEY_ALIAS / ASTIMER_KEY_PASSWORD."
    )
}

android {
    namespace = "com.vibes.autosilenttimer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.vibes.autosilenttimer"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFilePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.3")

    testImplementation("junit:junit:4.13.2")
}
