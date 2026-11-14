plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    androidLibrary {
        namespace = "app.nextdata.shared"
        compileSdk = 36
        minSdk = 26
        androidResources { enable = true }
        withHostTest { isIncludeAndroidResources = false }
    }
    jvm()

    sourceSets {
        // Source set shared by the two JVM-based targets (desktop jvm + android): platform formatters.
        val jvmCommonMain by creating { dependsOn(commonMain.get()) }
        jvmMain.get().dependsOn(jvmCommonMain)
        androidMain.get().dependsOn(jvmCommonMain)
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.compose.icons.extended)
            implementation(libs.compose.ui.backhandler)
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "app.nextdata.shared.resources"
    generateResClass = always
}

tasks.configureEach {
    if (name.endsWith("AarMetadata")) enabled = false
}
