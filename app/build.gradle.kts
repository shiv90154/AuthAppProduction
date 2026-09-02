import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing credentials live in local.properties (gitignored, never
// committed) — see README/keystore/ for how these were generated. Falls
// back to null (unsigned release) if a dev hasn't set these up locally,
// so `assembleRelease` still works for anyone without the keystore.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.example.myapplication"
    ndkVersion = "27.0.12077973"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        // Play Store identity — PERMANENT once published, cannot ever be
        // changed. Deliberately different from `namespace` (which stays
        // com.example.myapplication so the generated R class and every
        // JNI symbol `Java_com_example_myapplication_*` in native-lib.cpp /
        // MidiProcessor.cpp keep working untouched). applicationId and
        // namespace are independent by design.
        applicationId = "com.arunspd30.octapad"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake {
                cppFlags += ""
            }
        }
    }

    val releaseStoreFile = localProps.getProperty("RELEASE_STORE_FILE")
    if (releaseStoreFile != null) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = localProps.getProperty("RELEASE_STORE_PASSWORD")
                keyAlias = localProps.getProperty("RELEASE_KEY_ALIAS")
                keyPassword = localProps.getProperty("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 / minify is intentionally OFF for now.
            //
            // It is NOT required to publish on Play — the only downsides of
            // leaving it off are a larger APK and debug Log.* calls shipping
            // in the binary. Turning it on in this AGP version (9.x) needs
            // the experimental "gradual R8" path
            // (optimization.enable=true + android.r8.gradual.support=true in
            // gradle.properties), which can't be validated here without a
            // device to test the shrunk release build against.
            //
            // To enable it later, on a machine where you can test the
            // release build end-to-end (kits load/save, factory sounds,
            // backup/restore, MIDI/JNI, activation):
            //   1) add `android.r8.gradual.support=true` to gradle.properties
            //   2) set `optimization { enable = true }` here
            //   3) keep rules are already written in
            //      app/src/main/keepRules/rules.keep
            // If assembleRelease then fails, add a `-keep` for whatever is
            // reached only by reflection/JNI — don't just turn it back off.
            optimization {
                enable = false
            }
            if (releaseStoreFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}