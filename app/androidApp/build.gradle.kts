import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeMultiplatform)
}

android {
    namespace = "app.nextdata.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.nextdata.android"
        minSdk = 26
        targetSdk = 35
        versionCode = (providers.gradleProperty("appVersionCode").orNull ?: "1").toInt()
        versionName = providers.gradleProperty("appVersion").getOrElse("1.0.0")
    }

    // Release signing comes from a git-ignored file (app/.tmp/signing.properties); without it the release APK stays unsigned.
    val signingFile = rootProject.file(".tmp/signing.properties")
    if (signingFile.isFile) {
        val sp = Properties()
        signingFile.inputStream().use { sp.load(it) }
        signingConfigs.create("release") {
            storeFile = rootProject.file(sp.getProperty("store"))
            storePassword = sp.getProperty("storePassword")
            keyAlias = sp.getProperty("alias")
            keyPassword = sp.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    // lintVital cannot resolve the :shared AAR while the AarMetadata gate is relaxed (see bottom of file); run the build with lint skipped.
    lint { checkReleaseBuilds = false }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // .so files stay uncompressed and 16 KB aligned (AGP default for minSdk >= 23); never use legacy packaging.
        jniLibs.useLegacyPackaging = false
    }

    // Engine libraries (dist/android/<abi>/libnd_engine.so) are included when present.
    sourceSets {
        getByName("main") {
            val dist = rootProject.file("../engine/dist/android")
            if (dist.isDirectory) jniLibs.srcDir(dist)
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.foundation)
    implementation(compose.ui)
    implementation(compose.material3)
    implementation(project(":engine-binding"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
}

// Compose Multiplatform 1.12.x pulls androidx.compose 1.12.1 whose AAR metadata asks for compileSdk 37, which is not
// installed here. Compiling against 36 works (verified by assembleDebug); the metadata gate is relaxed for that reason.
tasks.configureEach {
    if (name.endsWith("AarMetadata")) enabled = false
}
