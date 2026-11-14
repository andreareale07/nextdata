import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    jvmToolchain(21)
    compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":engine-binding"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.components.resources)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.jna.platform)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}

// The engine DLL is bundled as a classpath resource when it exists (extracted to %LOCALAPPDATA%\NextData\native at runtime).
val engineDist = rootProject.layout.projectDirectory.dir("../engine/dist")
val stageEngine by tasks.registering(Copy::class) {
    from(engineDist.dir("windows-x64")) { include("nd_engine.dll") }
    into(layout.buildDirectory.dir("generated/engine-res/native/windows-x64"))
}
sourceSets.main { resources.srcDir(layout.buildDirectory.dir("generated/engine-res")) }
tasks.named("processResources") { dependsOn(stageEngine) }

compose.desktop {
    application {
        mainClass = "app.nextdata.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "NextData"
            packageVersion = providers.gradleProperty("appVersion").getOrElse("1.0.0")
            description = "NextData remote storage client"
            vendor = "NextData"
            modules("jdk.unsupported", "java.naming", "java.sql")
            windows {
                menuGroup = "NextData"
                perUserInstall = true
                iconFile.set(project.file("icons/nextdata.ico"))
                upgradeUuid = "7d0f7a3e-5b1c-4c8e-9a52-3f6e2f0c9b11"
            }
        }
    }
}
