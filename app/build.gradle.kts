import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.bitlockerdroid"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.bitlockerdroid"
        minSdk = 33
        targetSdk = 34
        versionCode = 3
        versionName = "1.0.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        ndkVersion = "26.3.11579264"

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=none",
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
                )
            }
        }
    }

    signingConfigs {
        create("release") {
            // Check for keystore.properties in root project or app directory
            val rootProps = rootProject.file("keystore.properties")
            val appProps = file("keystore.properties")
            val propFile = when {
                rootProps.exists() -> rootProps
                appProps.exists() -> appProps
                else -> null
            }

            val keystoreProps = Properties()
            if (propFile != null && propFile.canRead()) {
                FileInputStream(propFile).use { keystoreProps.load(it) }
            }

            val storeFilePath = System.getenv("KEYSTORE_PATH")
                ?: keystoreProps.getProperty("STORE_FILE")
                ?: project.findProperty("RELEASE_STORE_FILE") as? String

            val candidateFile = storeFilePath?.let { path ->
                val f = file(path)
                if (f.exists()) f else rootProject.file(path)
            }

            if (candidateFile != null && candidateFile.exists()) {
                storeFile = candidateFile
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                    ?: keystoreProps.getProperty("STORE_PASSWORD")
                    ?: project.findProperty("RELEASE_STORE_PASSWORD") as? String
                    ?: ""
                keyAlias = System.getenv("KEY_ALIAS")
                    ?: keystoreProps.getProperty("KEY_ALIAS")
                    ?: project.findProperty("RELEASE_KEY_ALIAS") as? String
                    ?: ""
                keyPassword = System.getenv("KEY_PASSWORD")
                    ?: keystoreProps.getProperty("KEY_PASSWORD")
                    ?: project.findProperty("RELEASE_KEY_PASSWORD") as? String
                    ?: ""
            } else {
                // Dynamic fallback: if release keystore is absent, fall back to debug signing config
                val debugConfig = getByName("debug")
                initWith(debugConfig)

                // AGP does not auto-create debug.keystore when validating release signing configs.
                // If debug.keystore does not exist on disk (e.g. fresh CI machine), create it on the fly.
                val dFile = debugConfig.storeFile
                if (dFile != null && !dFile.exists()) {
                    dFile.parentFile?.mkdirs()
                    try {
                        val keytool = org.gradle.internal.jvm.Jvm.current().javaHome.resolve("bin/keytool").absolutePath
                        val pb = ProcessBuilder(
                            keytool, "-genkeypair", "-v",
                            "-keystore", dFile.absolutePath,
                            "-storepass", "android",
                            "-alias", "androiddebugkey",
                            "-keypass", "android",
                            "-keyalg", "RSA",
                            "-keysize", "2048",
                            "-validity", "10000",
                            "-dname", "CN=Android Debug,O=Android,C=US"
                        )
                        pb.redirectErrorStream(true)
                        val p = pb.start()
                        p.waitFor()
                    } catch (_: Exception) {}
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../native/CMakeLists.txt")
            version = "3.22.1"
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
        viewBinding = true
    }

    packaging {
        jniLibs {
            // Extract .so files to disk (extractNativeLibs=1). Some ROMs
            // (MIUI/HyperOS, etc.) have bugs loading uncompressed native libs
            // directly from the APK, which manifests as an instant crash with
            // no Java stack. Legacy packaging avoids that.
            useLegacyPackaging = true
        }
        resources {
            // Avoid common duplicate META-INF entries from dependencies.
            excludes += setOf(
                "META-INF/*.version",
                "META-INF/*.kotlin_module",
                "META-INF/versions/9/previous-compilation-data.bin"
            )
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Keystore-based key protection
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Non-root USB Mass Storage support
    implementation("me.jahnen.libaums:core:0.10.0")
}

