plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.androidsurround"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.androidsurround"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }
    }

    val envKeystorePath = System.getenv("KEYSTORE_PATH")
    val envKeystorePass = System.getenv("KEYSTORE_PASSWORD")
    val envKeyAlias = System.getenv("KEY_ALIAS")
    val envKeyPass = System.getenv("KEY_PASSWORD")

    if (envKeystorePath != null && envKeystorePass != null && envKeyAlias != null) {
        signingConfigs {
            create("ci") {
                storeFile = file(envKeystorePath)
                storePassword = envKeystorePass
                keyAlias = envKeyAlias
                keyPassword = envKeyPass ?: envKeystorePass
            }
        }
    } else {
        signingConfigs {
            create("ci") {
                // Use default debug keystore for local builds
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("ci")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("ci")
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

//    externalNativeBuild {
//        cmake {
//            path = file("src/main/native/CMakeLists.txt")
//        }
//    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.datasource.okhttp)
    debugImplementation(libs.androidx.ui.tooling)
}
