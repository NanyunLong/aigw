plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.aigw"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.aigw"
        minSdk = 24
        targetSdk = 34
        versionCode = 3
        versionName = "3.0"
    }

    signingConfigs {
        create("release") {
            val ks = System.getenv("AIGW_KEYSTORE")?.takeIf { it.isNotBlank() }
            if (ks != null && File(ks).exists()) {
                storeFile = File(ks)
                storePassword = System.getenv("AIGW_STORE_PASSWORD")
                keyAlias = System.getenv("AIGW_KEY_ALIAS")
                keyPassword = System.getenv("AIGW_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val ks = System.getenv("AIGW_KEYSTORE")?.takeIf { it.isNotBlank() }
            signingConfig = if (ks != null && File(ks).exists()) {
                signingConfigs.getByName("release")
            } else {
                null
            }
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
        buildConfig = true
        compose = true
    }

    composeOptions {
        // Kotlin 1.9.24 对应的 Compose Compiler 1.5.14
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }
}

dependencies {
    // 内嵌 HTTP 服务器（NanoHTTPD 2.3.1，Apache-2.0）
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    // JSON 解析
    implementation("org.json:json:20240303")

    // ---- Jetpack Compose 原生 UI（照 loomy 一样用 Compose）----
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    // 生命周期
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
}