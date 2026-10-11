plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.arcrictor.hermes"
    // API 37 platform is documented but not published to the SDK repo yet (max is 36).
    // The Pixel runs Android 17; 36 builds and runs on it fine. Bump to 37 when published.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.arcrictor.hermes"
        minSdk = 36
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-phase1"
    }

    // Debug signing from CI secrets when present; otherwise the default debug key.
    // Secrets (set by the owner, see AGENTS.md): DEBUG_KEYSTORE_B64,
    // DEBUG_KEYSTORE_PASSWORD, DEBUG_KEY_ALIAS, DEBUG_KEY_PASSWORD.
    // The workflow decodes DEBUG_KEYSTORE_B64 to $DEBUG_KEYSTORE_PATH.
    signingConfigs {
        create("ciDebug") {
            val path = System.getenv("DEBUG_KEYSTORE_PATH")
            if (path != null) {
                storeFile = file(path)
                storePassword = System.getenv("DEBUG_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("DEBUG_KEY_ALIAS")
                keyPassword = System.getenv("DEBUG_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            if (System.getenv("DEBUG_KEYSTORE_PATH") != null) {
                signingConfig = signingConfigs.getByName("ciDebug")
            }
        }
        release {
            isMinifyEnabled = false
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
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.tooling.preview)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
