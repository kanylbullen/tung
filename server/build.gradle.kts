import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// tung-server: the optional backend (accounts, API tokens, the REST API,
// export). A Ktor application on the JVM that shares the domain code with the
// app through :core. It must never depend on :app.
plugins {
    application
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Bytecode 17 like :core and :app; the Docker image runs it on a JRE 21.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

application {
    mainClass.set("io.github.kanylbullen.tung.server.ApplicationKt")
    applicationName = "tung-server"
}

val ktorVersion = "3.0.3"
val exposedVersion = "0.56.0"
val flywayVersion = "10.21.0"
val mcpSdkVersion = "0.5.0"

dependencies {
    implementation(project(":core"))

    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-auth:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-cors:$ktorVersion")
    implementation("io.ktor:ktor-server-rate-limit:$ktorVersion")
    implementation("io.ktor:ktor-server-forwarded-header:$ktorVersion")
    implementation("io.ktor:ktor-server-swagger:$ktorVersion")

    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("com.zaxxer:HikariCP:6.2.1")
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("org.flywaydb:flyway-core:$flywayVersion")
    implementation("org.flywaydb:flyway-database-postgresql:$flywayVersion")

    // Argon2id in pure Java: no native library to ship in the image.
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")
    implementation("ch.qos.logback:logback-classic:1.5.12")

    // The MCP SDK's protocol types and Server class. 0.5.0 is the newest release
    // built against Ktor 3.0.x (0.6+ needs Ktor 3.2 and Kotlin 2.2); its own Ktor
    // integration only speaks the old SSE transport, so McpRoutes implements
    // streamable HTTP over Server itself and the SDK's Ktor engines are left out.
    implementation("io.modelcontextprotocol:kotlin-sdk:$mcpSdkVersion") {
        exclude(group = "io.ktor")
    }

    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.0.21")
    testImplementation("org.testcontainers:postgresql:1.20.4")
    testImplementation("org.yaml:snakeyaml:2.3")
}

// The exercise catalogue is read from the classpath as /catalog/core.json,
// straight from the repo's catalog/ directory (the app ships the same files as
// assets). Added to processResources rather than as a resource srcDir, because
// a srcDir include filter would also apply to the other resource directories.
// The version MCP clients see as serverInfo.version.
version = "0.1.0"

tasks.processResources {
    val serverVersion = project.version.toString()
    inputs.property("version", serverVersion)
    filesMatching("tung-server.properties") { expand("version" to serverVersion) }
    from("../catalog") {
        include("*.json")
        into("catalog")
    }
    // The landing page's typeface and icon: the repository's one copy of each.
    from("../app/src/main/res/font") {
        include("archivo_variable.ttf")
        into("site")
    }
    from("../app/src/main/assets/licenses") {
        include("archivo-OFL.txt")
        into("site")
    }
    from("../web/src/jsMain/resources/icons") {
        include("icon.svg")
        into("site")
    }
}

tasks.test {
    // Testcontainers needs Docker. Docker Engine 29 refuses the API version the
    // docker-java client in Testcontainers 1.20 asks for by default.
    systemProperty("api.version", "1.44")
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
