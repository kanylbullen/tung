package io.github.kanylbullen.tung.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.DatabaseConfig
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.io.Closeable

/** The connection pool and the Exposed handle on it. Closed when the application stops. */
class Db(private val dataSource: HikariDataSource) : Closeable {
    // One attempt: Exposed would otherwise repeat a transaction up to three
    // times on any SQLException, a unique violation included.
    val database: Database = Database.connect(dataSource, databaseConfig = DatabaseConfig { defaultMaxAttempts = 1 })

    suspend fun <T> tx(block: Transaction.() -> T): T = newSuspendedTransaction(Dispatchers.IO, database) { block() }

    override fun close() = dataSource.close()

    companion object {
        /**
         * Opens the pool and runs the Flyway migrations. Throws [ConfigException]
         * with a readable message when Postgres cannot be reached, so the server
         * stops at start instead of failing on the first request.
         */
        fun connect(config: ServerConfig): Db {
            val hikari = HikariConfig().apply {
                jdbcUrl = config.databaseUrl
                username = config.databaseUser
                password = config.databasePassword
                maximumPoolSize = 10
                poolName = "tung"
            }
            val dataSource = try {
                HikariDataSource(hikari)
            } catch (e: Exception) {
                val cause = generateSequence<Throwable>(e) { it.cause }.last()
                throw ConfigException(
                    "Cannot connect to Postgres at ${config.databaseUrl.substringBefore('?')}: ${cause.message}. " +
                        "Check DATABASE_URL, DATABASE_USER and DATABASE_PASSWORD, and that the database is up.",
                )
            }
            try {
                Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate()
            } catch (e: Exception) {
                dataSource.close()
                throw e
            }
            return Db(dataSource)
        }
    }
}
