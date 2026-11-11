plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Imzalama bilgisi GitHub Actions'tan ortam değişkeni olarak gelir (bkz. .github/workflows/build.yml).
// Anahtar yoksa (yerel build, fork) release APK debug anahtarıyla imzalanır, build bozulmaz.
val keystorePath: String? = System.getenv("ORBIT_KEYSTORE_PATH")?.takeIf { File(it).exists() }

android {
    namespace = "app.orbit.island"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.orbit.island"
        minSdk = 34
        targetSdk = 34
        // Her Actions çalışmasında artar, böylece yeni APK eskisinin üstüne güncelleme olarak kurulur.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "0.1.1"
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = File(keystorePath)
                storePassword = System.getenv("ORBIT_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ORBIT_KEY_ALIAS")
                keyPassword = System.getenv("ORBIT_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystorePath != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Xposed API'si Vector/LSPosed tarafından çalışma zamanında sağlanır, APK'ya girmez.
    compileOnly(project(":xposed-api"))
}
