import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The shared data layer of the phone app (app/), the watch app (wear/) and,
// later, the iOS app: Room (entities, DAOs, TungDatabase), the repositories,
// the one CommandHandler, sync with tung-server, the settings in DataStore and
// the container that holds them. A Kotlin Multiplatform module: what every
// client runs the same way lives in commonMain (Room 2.8's multiplatform API,
// DataStore's core, Ktor), what needs Android (WorkManager, notifications,
// alarms, Health Connect, EncryptedSharedPreferences, the Wear Data Layer) in
// androidMain, and iosMain holds the iOS actuals. The iOS klibs compile on
// Linux like core's; linking needs a Mac. Neither app touches Room directly;
// see CLAUDE.md, "Layers".
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("androidx.room")
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    iosArm64()
    iosSimulatorArm64()

    compilerOptions {
        // Room's @ConstructedBy needs an `expect object`; expect/actual classes are still Beta.
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

            // Room: the apps read the repositories' Flows of entities, so the entity types are API.
            // 2.8 is Room's multiplatform line; androidx.sqlite is the driver API (SQLiteConnection).
            api("androidx.room:room-runtime:2.8.5")
            api("androidx.sqlite:sqlite:2.6.2")

            // Small state outside Room (the rest timer, sync's settings). At least 1.1.3: before it a read
            // that raced the file's very first write (a fresh install) threw FileNotFoundException.
            api("androidx.datastore:datastore-preferences-core:1.1.7")

            // Sync with tung-server and the assistant's provider: Ktor client, kotlinx JSON; the engine is each platform's.
            api("io.ktor:ktor-client-core:3.0.3")
            // Ktor's IOException is kotlinx-io's; Ktor only brings it at run time.
            implementation("org.jetbrains.kotlinx:kotlinx-io-core:0.5.4")
            implementation("io.ktor:ktor-client-content-negotiation:3.0.3")
            implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.3")
        }
        androidMain.dependencies {
            api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
            // 1.17.0 for NotificationCompat's Live Update on Android 16 (setRequestPromotedOngoing and
            // setShortCriticalText, ongoing/OngoingWorkoutNotifier.kt); it needs compileSdk 36.
            implementation("androidx.core:core-ktx:1.17.0")
            api("androidx.room:room-ktx:2.8.5")
            api("androidx.datastore:datastore-preferences:1.1.7")

            // OkHttp as Ktor's engine, WorkManager for the background sync, the session token in EncryptedSharedPreferences.
            implementation("io.ktor:ktor-client-okhttp:3.0.3")
            api("androidx.work:work-runtime-ktx:2.10.0")
            implementation("androidx.security:security-crypto:1.0.0")

            // Push (issue #34): an empty "sync now" from the server. FCM when Play services and a
            // Firebase app are there (the apps apply the google-services plugin only when they have a
            // google-services.json, so the open-source build runs with FCM off), else UnifiedPush.
            implementation(project.dependencies.platform("com.google.firebase:firebase-bom:33.7.0"))
            implementation("com.google.firebase:firebase-messaging")
            implementation("org.unifiedpush.android:connector:2.5.0")

            // Health Connect (issue #45): write finished workouts, read heart rate and calories. The stable 1.1.0
            // (October 2025, no change since rc03) needs compileSdk 36, which the build has since PR #95; nothing
            // the gateway calls moved between beta01 and it. Only the phone builds the real gateway; the watch
            // carries the library unused.
            implementation("androidx.health.connect:connect-client:1.1.0")
        }
        iosMain.dependencies {
            // SQLite compiled from source, the same on every iPhone; Ktor's engine over NSURLSession.
            implementation("androidx.sqlite:sqlite-bundled:2.6.2")
            implementation("io.ktor:ktor-client-darwin:3.0.3")
        }
        androidUnitTest.dependencies {
            implementation("junit:junit:4.13.2")
            implementation("org.robolectric:robolectric:4.14.1")
            implementation("androidx.test:core-ktx:1.6.1")
            implementation("com.squareup.okhttp3:mockwebserver:4.12.0")
            // MigrationTest: opens version 1 from data/schemas and runs the migrations to the current one.
            implementation("androidx.room:room-testing:2.8.5")
            implementation("androidx.test:runner:1.6.2")
            // FakeSyncServer and MemoryTokenStore, shared with the tests of app/ and wear/.
            implementation(project(":data-fixtures"))
        }
    }
}

android {
    namespace = "io.github.kanylbullen.tung.data"
    compileSdk = 36
    // Pinned like the apps': AGP's default build-tools is not compileSdk's.
    buildToolsVersion = "36.0.0"

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets.getByName("main") {
        // catalog/core.json and catalog/extended.json ship as assets of both apps, read straight from the repo.
        assets.srcDirs("../catalog")
    }
    sourceSets.getByName("test") {
        // The committed Room schemas, which MigrationTestHelper reads from the test's assets.
        assets.srcDirs("$projectDir/schemas")
    }

    androidResources {
        // AGP's default pattern, plus what in catalog/ is not data: the tools, the README and
        // the CC0 text. Assets have no per-directory exclude, so this pattern is the filter.
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:<dir>_*:!CVS:!thumbs.db:!picasa.ini:!*~" +
            ":!<dir>tools:!README.md:!LICENSE"
    }

    testOptions {
        // DirtyTrackingTest and SyncClientTest run Room and DataStore under Robolectric.
        unitTests.isIncludeAndroidResources = true
    }
}

room {
    // Every schema version is committed (data/schemas/), so a Migration can be tested against
    // the exact schema an installed phone has (CLAUDE.md, "Schema").
    schemaDirectory("$projectDir/schemas")
}

ksp {
    arg("room.generateKotlin", "true")
}

dependencies {
    // Room's processor runs once per target; the same schema comes out of each.
    add("kspAndroid", "androidx.room:room-compiler:2.8.5")
    add("kspIosArm64", "androidx.room:room-compiler:2.8.5")
    add("kspIosSimulatorArm64", "androidx.room:room-compiler:2.8.5")
}
