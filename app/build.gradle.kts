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
    namespace = "io.github.kanylbullen.tung"
    compileSdk = 36
    // Pinned, because AGP's default build-tools is not compileSdk's (8.9 defaults to 35.0.0):
    // without the pin a runner that only has 36.0.0 tries to download 35.0.0 into its SDK.
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "io.github.kanylbullen.tung"
        minSdk = 26
        // 36 since Play requires it (2026-08-31). What Android 16 changes for an app targeting
        // 36, and how each item was checked against this app, is in deploy/play.md, "targetSdk".
        targetSdk = 36
        // From the root build file: the phone's versionCode ends in 1, the watch's in 2
        // (wear/build.gradle.kts), because both apps share this applicationId and Play
        // wants every artefact of one app to carry its own versionCode.
        versionCode = buildNumber * 10 + 1
        versionName = rootProject.extra["versionName"] as String
        // The app's languages: English (values/, the default) and Swedish. Libraries bring
        // dozens of locales of their own; only these ship. Same list as res/xml/locales_config.xml.
        resourceConfigurations += listOf("en", "sv")
        // The server Settings starts with (issue #67): the public tung-server. A self-hoster's
        // fork changes this one line; anyone can still pick another server in Settings.
        buildConfigField("String", "DEFAULT_SERVER_URL", "\"https://tung.xuper.fun\"")
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
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        // What goes on our own phones: release code (R8, not debuggable). Signed with the
        // release key when it is in the environment, so it installs over a Play build and
        // keeps the data (`adb install -r -d`: its versionCode is lower), else with the debug
        // key, so it installs over a debug build. A debug build of a Compose app is several
        // times slower when scrolling (measured on the Note9, 2026-10-21).
        create("dogfood") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


    buildFeatures {
        compose = true
        // DEFAULT_SERVER_URL, and the version and build type in Settings' About.
        buildConfig = true
    }

    sourceSets.getByName("main") {
        java.srcDir("src/main/kotlin")
        // catalog/core.json and catalog/extended.json come as assets of the data module (data/).
    }

    androidResources {
        // res/xml/locales_config.xml is maintained by hand and referenced from the manifest.
        generateLocaleConfig = false
    }
    sourceSets.getByName("test") {
        java.srcDir("src/test/kotlin")
    }

    testOptions {
        // The flow tests (src/test/.../flows) run the real screens under Robolectric,
        // which needs the merged resources, assets and manifest.
        unitTests.isIncludeAndroidResources = true
    }
}

// A release is packaged only past the root's releaseGate: with the release key and a build number.
tasks.matching { it.name == "packageRelease" || it.name == "packageReleaseBundle" }
    .configureEach { dependsOn(rootProject.tasks.named("releaseGate")) }

/**
 * Where the flow tests save their screenshots, handed to the test JVM as a
 * system property. Declared as an output so a test task restored from the
 * build cache restores the PNGs too.
 */
abstract class FlowScreenshotsDir : CommandLineArgumentProvider {
    @get:OutputDirectory
    abstract val dir: DirectoryProperty

    override fun asArguments(): List<String> = listOf("-Dtung.flowScreenshots=${dir.get().asFile.absolutePath}")
}

// Debug only: the release variant runs the same flows, and two tasks cannot share one output directory.
tasks.withType<Test>().configureEach {
    if (name == "testDebugUnitTest") {
        jvmArgumentProviders += objects.newInstance<FlowScreenshotsDir>().apply {
            dir.set(layout.buildDirectory.dir("reports/flow-screenshots"))
        }
    }
}

dependencies {
    implementation(project(":core"))
    // Room, the repositories, the CommandHandler, sync and the rest timer, shared with wear/.
    implementation(project(":data"))

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-text")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // The same androidx.core as data/, which needs 1.17.0 for the Live Update.
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    implementation("androidx.datastore:datastore-preferences:1.1.7")

    // The Wearable Data Layer to the watch app in wear/ (wear/WearSyncService.kt).
    implementation("com.google.android.gms:play-services-wearable:19.0.0")

    // Sync itself is in data/; the app keeps WorkManager (TungApp is its Configuration.Provider)
    // and the process lifecycle for "sync when the app comes to the foreground".
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    // Sign in with Google (issue #51): Credential Manager with Play services' provider and the Google ID option.
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    // The fake tung-server of the sync tests (SyncClientTest, SyncFlowTest): a real HTTP
    // server in the test JVM, so the production OkHttp engine is what gets exercised.
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation(testFixtures(project(":data")))
    testImplementation("androidx.work:work-testing:2.10.0")
    // PushFlowTest calls the push receivers (data/push/) as their transports would.
    testImplementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    testImplementation("com.google.firebase:firebase-messaging")
    testImplementation("org.unifiedpush.android:connector:2.5.0")
    // Registers the empty activity that createComposeRule() hosts content in.
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
