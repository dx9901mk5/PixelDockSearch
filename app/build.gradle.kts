plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pixeldock.search"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pixeldock.search"
        minSdk = 26
        targetSdk = 35
        versionCode = 10
        versionName = "1.5.1"
    }

    signingConfigs {
        create("fixed") {
            val keystoreFile = file("pixeldocksearch-signing.p12")
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "pixeldocksearch2026"
                keyAlias = System.getenv("KEY_ALIAS") ?: "pixeldocksearch"
                keyPassword = System.getenv("KEY_PASSWORD") ?: "pixeldocksearch2026"
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        debug {
            if (file("pixeldocksearch-signing.p12").exists()) {
                signingConfig = signingConfigs.getByName("fixed")
            }
        }
        release {
            // Required by Google App's DrawerOverlayService (ILauncherOverlay) for non-system apps
            isDebuggable = true
            isMinifyEnabled = false
            if (file("pixeldocksearch-signing.p12").exists()) {
                signingConfig = signingConfigs.getByName("fixed")
            }
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
        buildConfig = true
        aidl = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    testImplementation("junit:junit:4.13.2")
}
