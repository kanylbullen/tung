// The shared Android data layer of the phone app (app/) and the watch app (wear/):
// Room (entities, DAOs, TungDatabase), the repositories, the one CommandHandler,
// sync with tung-server, the rest timer's Android side and TungContainer, which
// holds them. Neither app touches Room directly; see CLAUDE.md, "Layers".
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "io.github.kanylbullen.tung.data"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets.getByName("main") {
        java.srcDir("src/main/kotlin")
        // catalog/core.json and catalog/extended.json ship as assets of both apps, read straight from the repo.
        assets.srcDirs("../catalog")
    }
    sourceSets.getByName("test") {
        java.srcDir("src/test/kotlin")
    }

    androidResources {
        // AGP's default pattern, plus what in catalog/ is not data: the tools, the README and
        // the CC0 text. Assets have no per-directory exclude, so this pattern is the filter.
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:<dir>_*:!CVS:!thumbs.db:!picasa.ini:!*~" +
            ":!<dir>tools:!README.md:!LICENSE"
    }

    // FakeSyncServer and MemoryTokenStore, shared with the tests of app/ and wear/.
    testFixtures {
        enable = true
    }

    testOptions {
        // DirtyTrackingTest and SyncClientTest run Room and DataStore under Robolectric.
        unitTests.isIncludeAndroidResources = true
    }
}

ksp {
    arg("room.generateKotlin", "true")
}

dependencies {
    api(project(":core"))

    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("androidx.core:core-ktx:1.15.0")

    // Room: the apps read the repositories' Flows of entities, so the entity types are API.
    api("androidx.room:room-runtime:2.6.1")
    api("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Small state outside Room (the rest timer, sync's settings).
    api("androidx.datastore:datastore-preferences:1.1.1")

    // Sync with tung-server: Ktor client on OkHttp, kotlinx JSON, WorkManager for the
    // background sync, the session token in EncryptedSharedPreferences.
    implementation("io.ktor:ktor-client-okhttp:3.0.3")
    implementation("io.ktor:ktor-client-content-negotiation:3.0.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.3")
    api("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.security:security-crypto:1.0.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")

    testFixturesImplementation(project(":core"))
    testFixturesImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testFixturesImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}
