plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "local.pinger"
    compileSdk = 34

    defaultConfig {
        applicationId = "local.pinger"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    signingConfigs {
        getByName("debug") {
            // Persisted in the project so every build (each run in a throwaway container) signs
            // with the same key; otherwise a fresh random debug key breaks reinstall over adb.
            val keystore = rootProject.file("debug.keystore")
            // A fresh clone has no key (it is gitignored): create one with the JDK's keytool.
            if (!keystore.exists()) {
                val keytool = File(System.getProperty("java.home"), "bin/keytool").path
                ProcessBuilder(
                    keytool, "-genkeypair", "-keystore", keystore.path, "-storepass", "android",
                    "-alias", "androiddebugkey", "-keypass", "android", "-keyalg", "RSA", "-keysize", "2048",
                    "-validity", "10000", "-dname", "CN=Android Debug,O=Android,C=US",
                ).redirectErrorStream(true).start().also { it.inputStream.readBytes() }.waitFor()
            }
            storeFile = keystore
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}
