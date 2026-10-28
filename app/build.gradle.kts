import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
}

android {
    namespace = "com.evilknivel.phonewhisper"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.evilknivel.phonewhisper"
        minSdk = 30
        targetSdk = 36
        versionCode = 17
        versionName = "1.3.0"

        ndk { abiFilters += "arm64-v8a" }
    }

    // Release builds are signed with a fixed key from the environment (see .github/workflows/release.yml),
    // so new versions install over old ones. Without it they fall back to the debug key.
    val releaseKeystore = System.getenv("RELEASE_KEYSTORE_FILE")?.let(::file)?.takeIf { it.exists() }
    signingConfigs {
        if (releaseKeystore != null) create("release") {
            storeFile = releaseKeystore
            storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("RELEASE_KEY_ALIAS")
            keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // android.util.Log etc. return defaults instead of throwing in JVM tests.
            isReturnDefaultValues = true
            // Opt-in JNI integration test, see LocalTranscriberIntegrationTest.
            all { test ->
                System.getenv("SHERPA_JNI_DIR")?.let {
                    test.systemProperty("java.library.path", it)
                    test.testLogging.showStandardStreams = true
                }
            }
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("org.apache.commons:commons-compress:1.28.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
}
