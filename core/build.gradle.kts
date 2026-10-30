import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Domain logic without Android or the JVM: Kotlin Multiplatform, so the phone,
// the watch, the server and, later, an iOS app and a web client share one
// implementation. Only the JVM target is built today; an iOS target is added
// with the iOS app, and then commonMain compiles unchanged. Nothing in
// commonMain may import android.*, androidx.*, java.* or javax.*;
// NoJvmImportsTest enforces that. What needs the JVM (the locale's collator)
// lives in jvmMain.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
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
