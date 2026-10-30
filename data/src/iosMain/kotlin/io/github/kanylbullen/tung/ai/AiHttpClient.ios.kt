package io.github.kanylbullen.tung.ai

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout

/** NSURLSession, through Ktor's Darwin engine; redirects off like the Android client's. The engine has no connect timeout, so a host that never answers takes the socket timeout. */
actual fun aiHttpClient(timeoutMillis: Long): HttpClient = HttpClient(Darwin) {
    expectSuccess = false
    followRedirects = false
    install(HttpTimeout) {
        requestTimeoutMillis = timeoutMillis
        connectTimeoutMillis = minOf(timeoutMillis, 15_000L)
        socketTimeoutMillis = timeoutMillis
    }
}
