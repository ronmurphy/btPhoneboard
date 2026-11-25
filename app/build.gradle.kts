import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.ronmurphy.btphoneboard"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ronmurphy.btphoneboard"
        // Android 9 (API 28) is the first version with the BluetoothHidDevice API,
        // which is what lets the phone pretend to be a keyboard and mouse.
        minSdk = 28
        targetSdk = 36
        // GitHub Actions passes these in (see .github/workflows/build.yml) so
        // every build gets a higher number and installs as an update.
        // Builds on your own computer get the defaults.
        versionCode = (findProperty("appVersionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("appVersionName") as String?) ?: "0.1-dev"
    }

    signingConfigs {
        // One shared key, committed to the repo on purpose (see keystore/README.md),
        // so APKs from GitHub and from Android Studio all install over each other.
        create("shared") {
            storeFile = rootProject.file("keystore/shared.keystore")
            storePassword = "phoneboard"
            keyAlias = "phoneboard"
            keyPassword = "phoneboard"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Plain JVM unit tests for the HID report logic (no phone needed).
    testImplementation(libs.junit)
}
