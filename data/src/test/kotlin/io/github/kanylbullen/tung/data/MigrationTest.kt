package io.github.kanylbullen.tung.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Installed phones are upgraded in place, never wiped (CLAUDE.md, "Schema"):
 * a database created at an old version from its committed schema
 * (`data/schemas/`) is migrated to the current one, Room validates the result
 * against the current schema, and the rows written before survive.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), TungDatabase::class.java)

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun version1To2KeepsWorkoutsAndAddsTheHealthColumnsAsNull() {
        helper.createDatabase(DB, 1).apply {
            execSQL(
                "INSERT INTO workout (id, startedAt, endedAt, note, routineId, updatedAt, programDayId, dirty, startedBy) " +
                    "VALUES ('w1', 1000, 2000, 'Heavy', NULL, 2000, NULL, 0, 'phone')",
            )
            close()
        }

        helper.runMigrationsAndValidate(DB, 2, true, MIGRATION_1_2).use { db ->
            db.query("SELECT avgHeartRate, maxHeartRate, activeCalories, healthConnectId, note, startedBy FROM workout WHERE id = 'w1'").use { c ->
                c.moveToFirst()
                (0..3).forEach { assertEquals("column $it", true, c.isNull(it)) }
                assertEquals("Heavy", c.getString(4))
                assertEquals("phone", c.getString(5))
            }
        }
    }

    /** The builder the app uses opens a version-1 file with the registered migrations; Room reads the old row back. */
    @Test
    fun theAppsBuilderUpgradesAVersion1FileInPlace() = runBlocking {
        helper.createDatabase(DB, 1).apply {
            execSQL(
                "INSERT INTO workout (id, startedAt, endedAt, note, routineId, updatedAt, programDayId, dirty, startedBy) " +
                    "VALUES ('w1', 1000, 2000, NULL, NULL, 2000, NULL, 0, NULL)",
            )
            close()
        }

        val db = Room.databaseBuilder(context, TungDatabase::class.java, DB).addMigrations(*ALL_MIGRATIONS).build()
        try {
            val workout = db.workoutDao().getWorkout("w1")
            assertEquals(1000L, workout?.startedAt)
            assertNull(workout?.avgHeartRate)
            assertNull(workout?.maxHeartRate)
            assertNull(workout?.activeCalories)
            assertNull(workout?.healthConnectId)
        } finally {
            db.close()
        }
    }

    /** Each version has its committed schema and a migration from the one before, so no step is left to a wipe. */
    @Test
    fun everyVersionHasASchemaAndAMigration() {
        val db = TungDatabase.buildInMemory(context)
        val current = try {
            db.openHelper.readableDatabase.version
        } finally {
            db.close()
        }
        val schemas = context.assets.list(TungDatabase::class.java.name).orEmpty().map { it.removeSuffix(".json").toInt() }.sorted()
        assertEquals((1..current).toList(), schemas)
        assertEquals((1 until current).map { it to it + 1 }, ALL_MIGRATIONS.map { it.startVersion to it.endVersion })
    }

    private companion object {
        const val DB = "migration-test.db"
    }
}
