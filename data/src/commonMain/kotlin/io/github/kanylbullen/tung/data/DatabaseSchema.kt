package io.github.kanylbullen.tung.data

import io.github.kanylbullen.tung.core.catalogExerciseId

/**
 * What the database builders run on every platform, as plain SQL: the
 * migrations and the seed. Every schema change raises [TungDatabase]'s version
 * by one and adds a [MigrationStep] here, with a test in `MigrationTest`
 * against the committed schema (data/schemas/). Installed phones are upgraded
 * in place and never wiped (CLAUDE.md, "Schema").
 */
object DatabaseSchema {
    /** `updatedAt` of a seeded catalogue row that nobody has changed. */
    const val CATALOG_UPDATED_AT = 0L

    /** One seeded catalogue row: id, name, nameKey, muscleGroup, equipment (text), then updatedAt; `isCustom` and `dirty` are 0. */
    const val SEED_INSERT =
        "INSERT INTO exercise (id, name, nameKey, muscleGroup, equipment, isCustom, updatedAt, dirty) VALUES (?, ?, ?, ?, ?, 0, ?, 0)"

    /** A row of the seeded catalogue, in [SEED_INSERT]'s order. */
    class SeedRow(val id: String, val name: String, val nameKey: String, val muscleGroup: String, val equipment: String, val updatedAt: Long)

    /**
     * The catalogue to seed, inside the transaction that creates the schema:
     * each row's id is what [catalogExerciseId] derives from its key and its
     * `updatedAt` is [CATALOG_UPDATED_AT], so a freshly seeded catalogue row
     * is identical on every device. They are not dirty: an untouched
     * catalogue row is never pushed to a server, which has the same catalogue.
     */
    fun seedRows(): List<SeedRow> = SeedExercises.all.map { seed ->
        SeedRow(catalogExerciseId(seed.key), seed.name, seed.key, seed.muscle.key, seed.equipment.key, CATALOG_UPDATED_AT)
    }

    /** One schema version to the next: the statements, in order. */
    class MigrationStep(val from: Int, val to: Int, val statements: List<String>)

    val MIGRATIONS: List<MigrationStep> = listOf(
        // 1 → 2 (issue #45): heart rate, calories and the Health Connect session id on `workout`, all nullable.
        MigrationStep(
            1, 2,
            listOf(
                "ALTER TABLE `workout` ADD COLUMN `avgHeartRate` INTEGER",
                "ALTER TABLE `workout` ADD COLUMN `maxHeartRate` INTEGER",
                "ALTER TABLE `workout` ADD COLUMN `activeCalories` INTEGER",
                "ALTER TABLE `workout` ADD COLUMN `healthConnectId` TEXT",
            ),
        ),
        // 2 → 3 (issue #5): where an imported workout came from and which import wrote it, both nullable, and an index for undo.
        MigrationStep(
            2, 3,
            listOf(
                "ALTER TABLE `workout` ADD COLUMN `importSource` TEXT",
                "ALTER TABLE `workout` ADD COLUMN `importBatchId` TEXT",
                "CREATE INDEX IF NOT EXISTS `index_workout_importBatchId` ON `workout` (`importBatchId`)",
            ),
        ),
    )
}
