plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.meta.spatial.plugin)
    alias(libs.plugins.jetbrains.kotlin.plugin.compose)
}

val envVersionName = System.getenv("VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "1.0-dev"
val envVersionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1

val envKeystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
val envStorePassword = System.getenv("ANDROID_STORE_PASSWORD")
val envKeyAlias = System.getenv("ANDROID_KEY_ALIAS")
val envKeyPassword = System.getenv("ANDROID_KEY_PASSWORD")

val isSigningConfigured = !envKeystorePath.isNullOrBlank() &&
    !envStorePassword.isNullOrBlank() &&
    !envKeyAlias.isNullOrBlank() &&
    !envKeyPassword.isNullOrBlank() &&
    file(envKeystorePath).exists()

android {
    namespace = "com.jmez11.deadeasyplayer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jmez11.deadeasyplayer"
        minSdk = 29
        //noinspection OldTargetApi,ExpiredTargetSdkVersion
        targetSdk = 34
        versionCode = envVersionCode
        versionName = envVersionName

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (isSigningConfigured) {
            create("release") {
                storeFile = file(envKeystorePath!!)
                storePassword = envStorePassword
                keyAlias = envKeyAlias
                keyPassword = envKeyPassword
            }
        }
    }

    packaging { resources.excludes.add("META-INF/LICENSE") }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (isSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        
    }
}

dependencies {
    implementation("org.videolan.android:libvlc-all:3.5.1")
    // Meta Spatial SDK
    implementation(libs.meta.spatial.sdk.base)
    implementation(libs.meta.spatial.sdk.vr)
    implementation(libs.meta.spatial.sdk.toolkit)
    implementation(libs.meta.spatial.sdk.isdk)

    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Compose
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons)

    // Tests
    testImplementation("junit:junit:4.13.2")
}