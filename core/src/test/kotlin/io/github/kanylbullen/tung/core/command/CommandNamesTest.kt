package io.github.kanylbullen.tung.core.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandNamesTest {

    /** Every command class, found by plain Java reflection: they are nested in [Command]. */
    private val commandClasses: Set<Class<*>> =
        Command::class.java.declaredClasses.filter { Command::class.java.isAssignableFrom(it) }.toSet()

    @Test
    fun everyCommandHasAName() {
        assertTrue("found the commands", commandClasses.size >= 20)
        val missing = commandClasses - CommandNames.all.keys
        assertTrue("commands without a name: ${missing.map { it.simpleName }}", missing.isEmpty())
        assertEquals(commandClasses, CommandNames.all.keys)
    }

    @Test
    fun namesAreUniqueSnakeCase() {
        val names = CommandNames.all.values
        assertEquals(names.size, names.toSet().size)
        names.forEach { assertTrue(it, it.matches(Regex("[a-z]+(_[a-z]+)*"))) }
    }

    @Test
    fun namesRoundTrip() {
        assertEquals("log_set", CommandNames.of(Command.LogSet("w")))
        assertEquals("apply_plan", CommandNames.of(Command.ApplyPlan(WorkoutPlan(emptyList()))))
        assertEquals("start_workout", CommandNames.of(Command.StartWorkout()))
        assertEquals(Command.SetWeight::class.java, CommandNames.classOf("set_weight"))
        assertEquals(null, CommandNames.classOf("no_such_command"))
    }

    @Test
    fun aNameIsTheClassNameInSnakeCase() {
        // Not a rule for the future (a class may be renamed, its name may not), but true today.
        CommandNames.all.forEach { (type, name) ->
            assertEquals(type.simpleName.replace(Regex("(?<!^)([A-Z])"), "_$1").lowercase(), name)
        }
    }
}
