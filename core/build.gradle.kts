import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Domain logic without Android: plain Kotlin on the JVM, so the app, a future
// Ktor server and a Wear OS app share one implementation. Not Kotlin
// Multiplatform (yet). Nothing here may import android.* or androidx.*;
// NoAndroidImportsTest enforces that.
plugins {
    `java-library`
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Bytecode 17, the same target as the app (app/build.gradle.kts), compiled by
// whatever JDK runs Gradle. jvmToolchain(17) would require a JDK 17 install
// that neither the dev server nor the build otherwise needs.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Part of the public API: ExtendedExercise is @Serializable, ExtendedCatalog.all() suspends.
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    // Lets NoAndroidImportsTest find the sources regardless of the working directory.
    systemProperty("tung.core.mainSrc", layout.projectDirectory.dir("src/main").asFile.absolutePath)
}
