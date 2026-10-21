package io.github.kanylbullen.tung.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// Every schema change raises TungDatabase's version by one and adds a Migration
// here, registered in TungDatabase.build and buildInMemory, with a test in
// MigrationTest against the committed schema (data/schemas/). Installed phones
// are upgraded in place and never wiped (CLAUDE.md, "Schema").

/** 1 → 2 (issue #45): heart rate, calories and the Health Connect session id on `workout`, all nullable. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `workout` ADD COLUMN `avgHeartRate` INTEGER")
        db.execSQL("ALTER TABLE `workout` ADD COLUMN `maxHeartRate` INTEGER")
        db.execSQL("ALTER TABLE `workout` ADD COLUMN `activeCalories` INTEGER")
        db.execSQL("ALTER TABLE `workout` ADD COLUMN `healthConnectId` TEXT")
    }
}

/** 2 → 3 (issue #5): where an imported workout came from and which import wrote it, both nullable, and an index for undo. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `workout` ADD COLUMN `importSource` TEXT")
        db.execSQL("ALTER TABLE `workout` ADD COLUMN `importBatchId` TEXT")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_workout_importBatchId` ON `workout` (`importBatchId`)")
    }
}

/** Every migration, oldest first; both database builders register all of them. */
val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
