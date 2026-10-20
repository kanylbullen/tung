package io.github.kanylbullen.tung.server

import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll

/**
 * Routines and programs as read by the export. They are written only by sync
 * (no REST routes yet; the command routes will add them).
 */
class RoutineStore(private val db: Db) {

    /** The user's live routines with their live exercises in position order. */
    suspend fun routines(userId: String): List<RoutineDto> = db.tx {
        val exercises = RoutineExercises.selectAll()
            .where { (RoutineExercises.userId eq userId) and RoutineExercises.deletedAt.isNull() }
            .orderBy(RoutineExercises.position, SortOrder.ASC)
            .groupBy({ it[RoutineExercises.routineId] }) {
                RoutineExerciseDto(
                    id = it[RoutineExercises.id],
                    exerciseId = it[RoutineExercises.exerciseId],
                    position = it[RoutineExercises.position],
                    targetSets = it[RoutineExercises.targetSets],
                    targetReps = it[RoutineExercises.targetReps],
                    updatedAt = it[RoutineExercises.updatedAt],
                )
            }
        Routines.selectAll()
            .where { (Routines.userId eq userId) and Routines.deletedAt.isNull() }
            .orderBy(Routines.name, SortOrder.ASC)
            .map {
                RoutineDto(
                    id = it[Routines.id],
                    name = it[Routines.name],
                    lastUsedAt = it[Routines.lastUsedAt],
                    updatedAt = it[Routines.updatedAt],
                    exercises = exercises[it[Routines.id]].orEmpty(),
                )
            }
    }

    /** The user's live programs with their live days in position order. */
    suspend fun programs(userId: String): List<ProgramDto> = db.tx {
        val days = ProgramDays.selectAll()
            .where { (ProgramDays.userId eq userId) and ProgramDays.deletedAt.isNull() }
            .orderBy(ProgramDays.position, SortOrder.ASC)
            .groupBy({ it[ProgramDays.programId] }) {
                ProgramDayDto(
                    id = it[ProgramDays.id],
                    routineId = it[ProgramDays.routineId],
                    position = it[ProgramDays.position],
                    weekdays = it[ProgramDays.weekdays],
                    updatedAt = it[ProgramDays.updatedAt],
                )
            }
        Programs.selectAll()
            .where { (Programs.userId eq userId) and Programs.deletedAt.isNull() }
            .orderBy(Programs.name, SortOrder.ASC)
            .map {
                ProgramDto(
                    id = it[Programs.id],
                    name = it[Programs.name],
                    mode = it[Programs.mode],
                    isActive = it[Programs.isActive],
                    updatedAt = it[Programs.updatedAt],
                    days = days[it[Programs.id]].orEmpty(),
                )
            }
    }
}
