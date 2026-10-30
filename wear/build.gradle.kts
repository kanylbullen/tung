// The Wear OS app (see wear/README.md). Standalone when it has a server token:
// its own Room database through data/, commands run locally, sync over Wi-Fi.
// Companion otherwise: mirrors the phone's active workout over the Wearable
// Data Layer and sends the user's taps back as commands.
// Same applicationId as the phone app, which the Data Layer requires to pair
// the two, and the same signing key: the debug keystore for a debug pair, the one
// release key (root build.gradle.kts, TUNG_KEYSTORE…) for releases of both.
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

@Suppress("UNCHECKED_CAST")
val releaseSigning = rootProject.extra["releaseSigning"] as Map<String, String>?
val buildNumber = rootProject.extra["buildNumber"] as Int

android {
    namespace = "io.github.kanylbullen.tung.wear"
    compileSdk = 36
    // Pinned like the phone's: AGP's default build-tools is not compileSdk's.
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "io.github.kanylbullen.tung"
        // Wear OS 3 (Galaxy Watch 4 and later).
        minSdk = 30
        // Stays 35 on purpose: Play's bar for Wear OS apps is Android 15, and Android 16's
        // behaviour changes for apps targeting 36 are not wanted on the watch yet. The phone
        // app targets 36 (app/build.gradle.kts). compileSdk is the API a module compiles
        // against, not what it targets, and follows the phone's so the three Android modules
        // build against one platform.
        targetSdk = 35
        // From the root build file: the watch's versionCode ends in 2, the phone's in 1
        // (app/build.gradle.kts), because both apps share this applicationId and Play
        // wants every artefact of one app to carry its own versionCode.
        versionCode = buildNumber * 10 + 2
        versionName = rootProject.extra["versionName"] as String
        resourceConfigurations += listOf("en", "sv")
    }

    // The release key, when the four TUNG_KEYSTORE… inputs are set (root build.gradle.kts).
    // Without them the release build type is unsigned, and the root's releaseGate refuses to package it.
    signingConfigs {
        releaseSigning?.let { key ->
            create("release") {
                storeFile = rootProject.file(key.getValue("TUNG_KEYSTORE"))
                storePassword = key.getValue("TUNG_KEYSTORE_PASSWORD")
                keyAlias = key.getValue("TUNG_KEY_ALIAS")
                keyPassword = key.getValue("TUNG_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
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

// A release is packaged only past the root's releaseGate: with the release key and a build number.
tasks.matching { it.name == "packageRelease" || it.name == "packageReleaseBundle" }
    .configureEach { dependsOn(rootProject.tasks.named("releaseGate")) }

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
    constraints {
        // play-services-basement brings androidx.fragment 1.1.0, and lintVital refuses a release
        // with registerForActivityResult on a fragment below 1.3.0 (InvalidFragmentVersionForActivityResult);
        // the phone app already resolves 1.5.7 through its own dependencies. Not used directly.
        implementation("androidx.fragment:fragment:1.5.7") {
            because("lintVitalRelease: the ActivityResult APIs need androidx.fragment 1.3.0 or later")
        }
    }
    // The Ongoing Activity of a workout in progress (WatchOngoing.kt), on data's notification.
    implementation("androidx.wear:wear-ongoing:1.0.0")
    // The same androidx.core as data/, which needs 1.17.0 for the phone's Live Update.
    implementation("androidx.core:core-ktx:1.17.0")
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
