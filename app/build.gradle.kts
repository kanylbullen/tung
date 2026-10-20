plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "io.github.kanylbullen.tung"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.kanylbullen.tung"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // The app's languages: English (values/, the default) and Swedish. Libraries bring
        // dozens of locales of their own; only these ship. Same list as res/xml/locales_config.xml.
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

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // The Wearable Data Layer to the watch app in wear/ (wear/WearSyncService.kt).
    implementation("com.google.android.gms:play-services-wearable:19.0.0")

    // Sync itself is in data/; the app keeps WorkManager (TungApp is its Configuration.Provider)
    // and the process lifecycle for "sync when the app comes to the foreground".
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")

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
    // Registers the empty activity that createComposeRule() hosts content in.
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
