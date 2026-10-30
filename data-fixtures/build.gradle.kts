// Test doubles of data/'s seams, shared by the tests of data/, app/ and wear/:
// FakeSyncServer (tung-server on a MockWebServer), MemoryTokenStore,
// FakePushEnvironment and FakeHealthConnect. A module of its own because a
// Kotlin Multiplatform Android target has no test fixtures of its own; it is
// only ever a `testImplementation` dependency.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.kanylbullen.tung.data.fixtures"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets.getByName("main") {
        java.srcDir("src/main/kotlin")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":data"))
    // FakeSyncServer is a MockWebServer; the tests reach its address through it.
    api("com.squareup.okhttp3:mockwebserver:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    // FakeHealthConnect answers the permission sheet with an ActivityResultContract.
    implementation("androidx.activity:activity:1.9.3")
}
