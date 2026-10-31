import org.apache.tools.ant.filters.ReplaceTokens

// The web app: Kotlin/JS in the browser, a PWA (installable, works offline)
// over the same `core` as the phone, the watch and the server. The UI is
// Compose HTML (DOM elements as composables, the Compose runtime the phone's
// screens use), the data lives in the browser (web/README.md, CLAUDE.md "Web").
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Compose Multiplatform 1.10.x is the last line built with Kotlin 2.2
// (1.11 needs 2.3); the runtime and Compose HTML come as plain
// dependencies, so the root build needs no further plugin.
val composeVersion = "1.10.3"

kotlin {
    js {
        browser {
            commonWebpackConfig {
                // What index.html loads.
                outputFileName = "tung.js"
            }
            testTask {
                // The tests run in a real browser (Karma); Chrome is on the
                // hosted CI runner and on the dev server.
                useKarma { useChromeHeadless() }
            }
        }
        binaries.executable()
    }

    sourceSets {
        jsMain.dependencies {
            implementation(project(":core"))
            implementation("org.jetbrains.compose.runtime:runtime:$composeVersion")
            implementation("org.jetbrains.compose.html:html-core:$composeVersion")
            // kotlinx-datetime knows only UTC and fixed offsets in the browser without
            // a time-zone database; this one covers ten years around today (Main.kt loads it).
            implementation(npm("@js-joda/timezone", "2.3.0"))
        }
        jsTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
        }
    }
}

/**
 * What tells one build's service worker from the last: the commit, and the
 * time, so a rebuild of a dirty tree is a new version too. The worker drops
 * every cache that is not its own, so a new build replaces the shell on the
 * next load.
 */
val buildId: String = run {
    val commit = runCatching {
        providers.exec { commandLine("git", "rev-parse", "--short", "HEAD") }.standardOutput.asText.get().trim()
    }.getOrDefault("nogit")
    "$commit-${System.currentTimeMillis() / 1000}"
}

tasks.named<ProcessResources>("jsProcessResources") {
    // The shell's static files are the module's resources; the catalogue and
    // the typeface's licence are the repository's one copy of each, not copies.
    // The typeface itself is fonts/archivo_latin.woff2, a Latin subset of the
    // app's archivo_variable.ttf (web/README.md, "The typeface").
    from(rootProject.file("catalog")) {
        include("core.json", "extended.json")
        into("catalog")
    }
    from(rootProject.file("app/src/main/assets/licenses")) {
        include("archivo-OFL.txt")
        into("fonts")
    }
    // The build's id goes into the worker's cache name and into the shell's
    // URLs (tung.js?v=…, styles.css?v=…): a CDN in front of the server (the
    // instance's Cloudflare keeps static files for hours) then never hands a
    // new index.html an old bundle, since every build asks for new URLs.
    filesMatching(listOf("sw.js", "index.html")) {
        filter(ReplaceTokens::class, "tokens" to mapOf("buildId" to buildId))
    }
    inputs.property("buildId", buildId)
}
