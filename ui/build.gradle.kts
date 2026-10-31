import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The screens every client shares, in Compose Multiplatform: the phone app
// (app/) today and the iOS app later. A Kotlin Multiplatform module with an
// Android target and the iOS app's targets; what is drawn lives in
// commonMain, with Compose Resources (composeResources/) for what the screens
// load: the Archivo font today, the strings as the screens move here. The iOS
// klibs compile on Linux like core's and data's; linking needs a Mac. Moved
// one piece at a time (CLAUDE.md, "Layers"): the theme, then the Routines tab
// and the routine page with what they draw (the input sheet, the muscle map).
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
            // api: Res is public, and app/'s flow tests read it with getString.
            api(compose.components.resources)
            api(project(":core"))
            api(project(":data"))
            // The multiplatform lifecycle CMP 1.10 is built with; on Android it resolves to androidx.lifecycle.
            api("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel:2.9.6")
            api("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-savedstate:2.9.6")
            api("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.9.6")
            // The icons were frozen at 1.7.3 upstream; the same core set app/ uses.
            implementation("org.jetbrains.compose.material:material-icons-core:1.7.3")
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
    // Public, so app/'s flow tests can read the moved strings (str(Res.string.…)).
    publicResClass = true
    packageOfResClass = "io.github.kanylbullen.tung.ui.resources"
}
