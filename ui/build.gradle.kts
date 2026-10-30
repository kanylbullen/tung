import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The screens every client shares, in Compose Multiplatform: the phone app
// (app/) today and the iOS app later. A Kotlin Multiplatform module with an
// Android target and the iOS app's targets; what is drawn lives in
// commonMain, with Compose Resources (composeResources/) for what the screens
// load: the Archivo font today, the strings as the screens move here. The iOS
// klibs compile on Linux like core's and data's; linking needs a Mac. Moved
// one piece at a time (CLAUDE.md, "Layers"): the theme first.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(compose.runtime)
            api(compose.foundation)
            api(compose.material3)
            api(compose.ui)
            implementation(compose.components.resources)
        }
    }
}

android {
    namespace = "io.github.kanylbullen.tung.ui"
    compileSdk = 36
    // Pinned like the other Android modules': AGP's default build-tools is not compileSdk's.
    buildToolsVersion = "36.0.0"

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

compose.resources {
    // The generated accessors (Res.font.archivo_variable) stay inside this module.
    packageOfResClass = "io.github.kanylbullen.tung.ui.resources"
}
