package io.github.kanylbullen.tung.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json

/** NSURLSession, through Ktor's Darwin engine. */
actual fun syncHttpClient(): HttpClient = HttpClient(Darwin) {
    expectSuccess = false
    install(ContentNegotiation) { json(SyncJson) }
    install(HttpTimeout) {
        connectTimeoutMillis = 15_000
        socketTimeoutMillis = 60_000
        requestTimeoutMillis = 120_000
    }
}
