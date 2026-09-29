plugins {
    // AGP 9 compiles Kotlin itself; the kotlin-android plugin is gone.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.ncwardell.drysocks"
    // 37.1 because tor-android requires it (its AAR metadata says so). Compiling against
    // it changes nothing at run time; targetSdk below is what opts into new behaviour.
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }

    // Pinned rather than left to AGP's default, which would send Gradle off to install a
    // second copy of the build tools at configuration time. Must match flake.nix.
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "io.github.ncwardell.drysocks"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // engine.aar carries these two; say so, so an APK never claims more.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        // Store the Go library uncompressed and page-aligned, so it is mapped straight out
        // of the APK; required for 16 KB-page devices.
        jniLibs { useLegacyPackaging = false }
    }
}

dependencies {
    implementation(project(":core"))

    // Built by engine/build-aar.sh; not checked in.
    implementation(files("libs/engine.aar"))
    implementation(libs.tor.android)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
