// The Wear OS app (see wear/README.md). Standalone when it has a server token:
// its own Room database through data/, commands run locally, sync over Wi-Fi.
// Companion otherwise: mirrors the phone's active workout over the Wearable
// Data Layer and sends the user's taps back as commands.
// Same applicationId as the phone app, which the Data Layer requires to pair
// the two; both use the default debug keystore, so a debug pair is signed alike.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// FCM push (issue #34) needs a Firebase app, which only a build with this module's
// google-services.json has. Without the file the plugin is not applied, Firebase has
// no app, and the app picks UnifiedPush or no push instead: the open-source build
// compiles and runs with FCM off. The file is gitignored; see server/README.md, "Push".
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "io.github.kanylbullen.tung.wear"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.kanylbullen.tung"
        // Wear OS 3 (Galaxy Watch 4 and later).
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        resourceConfigurations += listOf("en", "sv")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    sourceSets.getByName("main") {
        java.srcDir("src/main/kotlin")
    }
    sourceSets.getByName("test") {
        java.srcDir("src/test/kotlin")
    }

    testOptions {
        // LocalWatchCommandsTest runs the shared data layer (Room, DataStore, assets, ex_* strings) under Robolectric.
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":core"))
    // Room, the repositories, the CommandHandler, sync and the rest timer, shared with app/.
    implementation(project(":data"))

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.wear.compose:compose-material:1.4.0")
    implementation("androidx.wear.compose:compose-foundation:1.4.0")
    implementation("androidx.wear.compose:compose-navigation:1.4.0")
    implementation("androidx.wear:wear-tooling-preview:1.0.0")

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    // The Ongoing Activity of a workout in progress (WatchOngoing.kt), on data's notification.
    implementation("androidx.wear:wear-ongoing:1.0.0")
    implementation("androidx.core:core-ktx:1.15.0")
    // TungWearApp is WorkManager's Configuration.Provider (sync in the background), and
    // syncs when the app comes to the foreground, as the phone does.
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation(testFixtures(project(":data")))
}
