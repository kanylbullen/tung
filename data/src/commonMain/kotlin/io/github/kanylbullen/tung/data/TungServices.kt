package io.github.kanylbullen.tung.data

import io.github.kanylbullen.tung.ai.AiService
import io.github.kanylbullen.tung.ai.AiSettings
import io.github.kanylbullen.tung.core.ExtendedCatalog
import io.github.kanylbullen.tung.core.command.CommandHandler
import io.github.kanylbullen.tung.importing.ImportService
import io.github.kanylbullen.tung.push.PushControl
import io.github.kanylbullen.tung.sync.SyncAccount
import io.github.kanylbullen.tung.timer.RestTimerStore
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/**
 * What the shared screens (`ui/`) need from a device's container: the
 * repositories for reads, [commands] for every change, the settings and
 * services behind Settings, and the time. The phone's and the watch's
 * `TungContainer` implement it, and so will the iPhone's; the navigation root
 * in `ui/` builds every ViewModel from it.
 */
interface TungServices {
    val workoutRepository: WorkoutRepository
    val routineRepository: RoutineRepository
    val programRepository: ProgramRepository
    val exerciseRepository: ExerciseRepository
    val extendedCatalog: ExtendedCatalog
    val catalogueMuscles: CatalogueMuscles
    val catalogueDetails: CatalogueDetails
    val commands: CommandHandler
    val imports: ImportService
    val restTimer: RestTimerStore
    val equipmentSettings: EquipmentSettings
    val progressSettings: ProgressSettings
    val pastWorkouts: PastWorkoutSettings
    val syncAccount: SyncAccount
    val push: PushControl
    val aiSettings: AiSettings
    val ai: AiService

    /** The wall clock in epoch millis; the flow tests' clock is settable. */
    fun nowMillis(): Long

    /** The date today in [zone], for Home's "This week" and "Today", Progress and the summary. */
    fun today(): LocalDate

    /** The device's time zone. */
    fun zone(): TimeZone
}
