plugins {
    // 8.9.1 is the lowest AGP that officially supports compileSdk 36, which Play's
    // targetSdk 36 requirement (since 2026-08-31) needs; 8.7.3 only warned that it was
    // untested against it. 8.9 needs Gradle 8.11.1, which the wrapper already is, and
    // keeps Kotlin 2.0.21 and KSP 2.0.21-1.0.28 as they are.
    id("com.android.application") version "8.9.1" apply false
    id("com.android.library") version "8.9.1" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.2.21" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.21" apply false
    id("com.google.devtools.ksp") version "2.2.21-2.0.5" apply false
    // FCM push: applied by app/ and wear/ only when they have a google-services.json (see their build files).
    id("com.google.gms.google-services") version "4.4.2" apply false
}

// ---- Release inputs shared by app/ and wear/, which read them from rootProject.extra. ----
// How a Play build is made: deploy/play.md and scripts/release.sh.

/**
 * A build input from the environment or a Gradle property (`-P`, gradle.properties).
 * The environment first: that is how scripts/release.sh hands them over. Blank counts
 * as missing: an `export TUNG_KEYSTORE=` without a value is a mistake, not a choice.
 */
fun buildInput(name: String): String? =
    (System.getenv(name) ?: findProperty(name) as String?)?.takeIf { it.isNotBlank() }

/** A path from the inputs: `~/` is the home directory, since a `-P` value is not shell-expanded. */
fun inputPath(value: String): File =
    rootProject.file(if (value.startsWith("~/")) System.getProperty("user.home") + value.drop(1) else value)

/**
 * The release key for both apps, or null when any of the four inputs is missing.
 *
 * Lazy on purpose: CI builds debug and runs `test`, which configures the release
 * variant, without a key, and a signing config that demanded its inputs when it was
 * configured would fail that. Without the key the release build type is unsigned and
 * `releaseGate` below refuses to package it.
 *
 * The key is never in the repository (`*.jks` and `*.keystore` are ignored); the
 * owner's lives outside the checkout, by convention `~/.config/tung/release.jks`. The
 * watch app is signed with the same key as the phone app: the Data Layer only pairs
 * apps with the same signing key, and so an update must carry the key of the install
 * it replaces, or the app has to be uninstalled first.
 */
val releaseSigning: Map<String, String>? =
    listOf("TUNG_KEYSTORE", "TUNG_KEYSTORE_PASSWORD", "TUNG_KEY_ALIAS", "TUNG_KEY_PASSWORD")
        .let { names ->
            names.mapNotNull { name -> buildInput(name)?.let { name to it } }
                .toMap()
                .takeIf { it.size == names.size }
        }

/**
 * The build number, `TUNG_VERSION_CODE`. scripts/release.sh derives it from the number
 * of commits on main (linear history, never force-pushed, so it only grows); a debug or
 * dogfood build without it is number 1. The phone's versionCode is `number * 10 + 1`
 * and the watch's `number * 10 + 2`: the two apps share an applicationId, and Play
 * wants every artefact of one app to carry its own versionCode.
 */
val versionCodeInput: String? = buildInput("TUNG_VERSION_CODE")
val buildNumber: Int = versionCodeInput?.let { raw ->
    raw.toIntOrNull()?.takeIf { it >= 1 }
        ?: throw GradleException("TUNG_VERSION_CODE is not a positive integer: $raw")
} ?: 1

/** The version people see (Settings → About). A release build appends its build number, so App info tells two builds apart. */
val baseVersionName = "0.1.0"

extra["releaseSigning"] = releaseSigning?.let { it + ("TUNG_KEYSTORE" to inputPath(it.getValue("TUNG_KEYSTORE")).path) }
extra["buildNumber"] = buildNumber
extra["versionName"] = if (versionCodeInput != null) "$baseVersionName.$buildNumber" else baseVersionName

/**
 * Refuses a release build without the key or the build number, before anything is
 * packaged. app/ and wear/ make their `packageRelease` and `packageReleaseBundle`
 * depend on it, so `assembleRelease` and `bundleRelease` stop here while the release
 * variant's unit tests (`test`, in CI) are untouched. A release signed with the debug
 * key would look fine and could neither be uploaded nor update a Play install, and a
 * release without a build number would carry versionCode 11 every time.
 */
val releaseGate by tasks.registering {
    group = "verification"
    description = "Refuses a release build without the release key and TUNG_VERSION_CODE"
    val signed = releaseSigning != null
    val store = releaseSigning?.get("TUNG_KEYSTORE")?.let { inputPath(it) }
    val numbered = versionCodeInput != null
    doLast {
        val missing = buildList {
            if (!signed) add("TUNG_KEYSTORE, TUNG_KEYSTORE_PASSWORD, TUNG_KEY_ALIAS, TUNG_KEY_PASSWORD")
            else if (store?.isFile != true) add("TUNG_KEYSTORE is not a file: $store")
            if (!numbered) add("TUNG_VERSION_CODE (scripts/release.sh derives it from main)")
        }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "The release build refuses to package. Missing:\n" +
                    missing.joinToString("\n") { "  - $it" } +
                    "\nSee deploy/play.md."
            )
        }
    }
}

// ---- Kotlin/JS (core's js target and web/): where Node.js and Yarn come from. ----
// The plugin downloads both (pinned versions, the same on every machine and in
// CI), but it must not declare its distribution repositories itself: it adds
// them to the root project while the setup task resolves
// (AbstractSetupTask.withUrlRepo), which settings.gradle.kts forbids
// (FAIL_ON_PROJECT_REPOS), and it does so as long as downloadBaseUrl has a
// value. The two repositories are declared in settings.gradle.kts instead, and
// every spec (the root's, Yarn's, and each project's: Kotlin 2.2 has both
// levels) is told to leave the URL out.
fun org.jetbrains.kotlin.gradle.targets.js.EnvSpec<*>.repositoryIsInSettings() {
    downloadBaseUrl.set(null as String?)
}
plugins.withType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootPlugin> {
    the<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec>().repositoryIsInSettings()
}
plugins.withType<org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin> {
    the<org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootEnvSpec>().repositoryIsInSettings()
}
allprojects {
    plugins.withType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsPlugin> {
        the<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec>().repositoryIsInSettings()
    }
}
