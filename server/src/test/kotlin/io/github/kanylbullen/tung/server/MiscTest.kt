package io.github.kanylbullen.tung.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MiscTest {

    @Test
    fun healthzChecksTheDatabase() = tungTest {
        val response = client.get("/healthz")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ok", response.json()["status"]!!.str)
    }

    @Test
    fun unknownEndpointsAnswerWithTheErrorShape() = tungTest {
        val response = client.get("/api/v1/nothing-here")
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("not_found", response.errorCode())
    }

    @Test
    fun theSpecAndSwaggerUiAreServed() = tungTest {
        val spec = client.get("/openapi.yaml")
        assertEquals(HttpStatusCode.OK, spec.status)
        assertTrue(spec.bodyAsText().startsWith("openapi: 3.1.0"))
        val docs = client.get("/docs")
        assertEquals(HttpStatusCode.OK, docs.status)
        assertTrue("swagger" in docs.bodyAsText().lowercase())
    }

    @Test
    fun thePublicUrlBecomesTheSpecsServer() {
        assertTrue("\n  - url: https://tung.example.org\n" in openApiSpec("https://tung.example.org"))
        assertTrue("\n  - url: /\n" in openApiSpec(null))
    }

    @Test
    fun configFailsFastWithAReadableMessage() {
        val missing = assertFailsWith<ConfigException> { ServerConfig.fromEnv(emptyMap()) }
        assertTrue("DATABASE_URL" in missing.message.orEmpty())
        assertFailsWith<ConfigException> { ServerConfig.fromEnv(mapOf("DATABASE_URL" to "postgres://x")) }
        assertFailsWith<ConfigException> { ServerConfig.fromEnv(mapOf("DATABASE_URL" to "jdbc:postgresql://x/y", "PORT" to "http")) }
        val ok = ServerConfig.fromEnv(
            mapOf("DATABASE_URL" to "jdbc:postgresql://db/tung", "PORT" to "9000", "TUNG_PUBLIC_URL" to "https://t.example/", "TUNG_TRUST_PROXY" to "true"),
        )
        assertEquals(9000, ok.port)
        assertEquals("https://t.example", ok.publicUrl)
        assertTrue(ok.trustProxy)
    }

    @Test
    fun anUnreachableDatabaseIsAConfigError() {
        val config = ServerConfig(databaseUrl = "jdbc:postgresql://127.0.0.1:1/tung?connectTimeout=2", databaseUser = "x", databasePassword = "y")
        val error = assertFailsWith<ConfigException> { Db.connect(config) }
        assertTrue("Cannot connect to Postgres" in error.message.orEmpty(), error.message)
    }

    @Test
    fun passwordsHashWithArgon2id() {
        val hash = Passwords.hash("hunter22 hunter22")
        assertTrue(hash.startsWith("\$argon2id\$v=19\$m=19456,t=2,p=1\$"), hash)
        assertTrue(Passwords.verify("hunter22 hunter22", hash))
        assertFalse(Passwords.verify("hunter22 hunter23", hash))
        assertFalse(Passwords.verify("anything", "not a hash"))
        assertTrue(Passwords.hash("same") != Passwords.hash("same"), "salted")
    }
}
