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
        versionCode = 2
        versionName = "2.0"
    }

    signingConfigs {
        create("release") {
            // 注意：环境变量未设置时是 null，设置为空串时是 ""，两者都要当成"没配"
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
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }
}

dependencies {
    // 全项目唯一的第三方依赖：NanoHTTPD 2.3.1（Apache-2.0，纯 Java，约 60KB，无出网逻辑）
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}