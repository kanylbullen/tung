import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Domain logic without Android or the JVM: Kotlin Multiplatform, so the phone,
// the watch, the server, the web app and, later, an iOS app share one
// implementation. The JVM target serves the Android modules and the server,
// the JS target the web app, and the iOS targets are compiled (not linked)
// here so commonMain stays iOS-clean. Nothing in commonMain may import
// android.*, androidx.*, java.* or javax.*; NoJvmImportsTest enforces that.
// What needs the JVM (the locale's collator) lives in jvmMain, and the
// browser's equivalent in jsMain.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    // The iOS app's targets: their klibs compile on any host (no Xcode needed
    // for that), so CI proves commonMain is iOS-clean; linking and running
    // need a Mac.
    iosArm64()
    iosSimulatorArm64()

    // The web app's target (web/): the browser's own JavaScript. Only the
    // library is built here (no executable), and its collation is jsMain's
    // Intl.Collator (BrowseJs.kt).
    js {
        browser()
    }

    jvm {
        // Bytecode 17, the same target as the app (app/build.gradle.kts), compiled by
        // whatever JDK runs Gradle. jvmToolchain(17) would require a JDK 17 install
        // that neither the dev server nor the build otherwise needs.
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        commonMain.dependencies {
            // Part of the public API: ExtendedExercise is @Serializable, ExtendedCatalog.all() suspends,
            // and dates are kotlinx-datetime's LocalDate and TimeZone.
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            api("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
        }
        jvmTest.dependencies {
            implementation("junit:junit:4.13.2")
        }
    }
}

tasks.named<Test>("jvmTest") {
    // Lets NoJvmImportsTest find the sources regardless of the working directory.
    systemProperty("tung.core.commonSrc", layout.projectDirectory.dir("src/commonMain").asFile.absolutePath)
}

// A multiplatform module has no `test` task of its own; this alias keeps
// `./gradlew test` (and CI) running core's tests as before.
tasks.register("test") {
    group = "verification"
    description = "Runs core's tests (an alias of jvmTest)."
    dependsOn("jvmTest")
}
