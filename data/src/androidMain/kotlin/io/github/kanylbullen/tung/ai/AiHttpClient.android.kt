package io.github.kanylbullen.tung.ai

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout

/** OkHttp with its own retry on a failed connection and its redirects off, besides Ktor's. */
actual fun aiHttpClient(timeoutMillis: Long): HttpClient = HttpClient(OkHttp) {
    expectSuccess = false
    followRedirects = false
    engine {
        config {
            followRedirects(false)
            followSslRedirects(false)
            retryOnConnectionFailure(false)
        }
    }
    install(HttpTimeout) {
        requestTimeoutMillis = timeoutMillis
        connectTimeoutMillis = minOf(timeoutMillis, 15_000L)
        socketTimeoutMillis = timeoutMillis
    }
}
