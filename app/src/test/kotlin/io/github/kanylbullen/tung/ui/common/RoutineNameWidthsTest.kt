package io.github.kanylbullen.tung.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

/** How a routine name's parts share a line that is too narrow: the day is kept whole, the group is what gets cut. */
class RoutineNameWidthsTest {

    @Test
    fun everythingWhenItFits() {
        assertEquals(RoutineNameWidths(100, 10, 200), routineNameWidths(available = 310, prefixWanted = 100, separatorWanted = 10, suffixWanted = 200, minPrefix = 40))
        assertEquals(RoutineNameWidths(100, 10, 200), routineNameWidths(available = 400, prefixWanted = 100, separatorWanted = 10, suffixWanted = 200, minPrefix = 40))
    }

    @Test
    fun theSuffixStaysWholeAndThePrefixTakesWhatIsLeft() {
        assertEquals(RoutineNameWidths(60, 10, 200), routineNameWidths(available = 270, prefixWanted = 100, separatorWanted = 10, suffixWanted = 200, minPrefix = 40))
        // Exactly the minimum is still a prefix.
        assertEquals(RoutineNameWidths(40, 10, 200), routineNameWidths(available = 250, prefixWanted = 100, separatorWanted = 10, suffixWanted = 200, minPrefix = 40))
    }

    @Test
    fun tooNarrowForAPrefixShowsTheSuffixAlone() {
        assertEquals(RoutineNameWidths(0, 0, 200), routineNameWidths(available = 230, prefixWanted = 100, separatorWanted = 10, suffixWanted = 200, minPrefix = 40))
        // And a suffix wider than the line is cut to it.
        assertEquals(RoutineNameWidths(0, 0, 150), routineNameWidths(available = 150, prefixWanted = 100, separatorWanted = 10, suffixWanted = 200, minPrefix = 40))
    }

    @Test
    fun anUnboundedLineFitsEverything() {
        assertEquals(RoutineNameWidths(100, 10, 200), routineNameWidths(available = Int.MAX_VALUE, prefixWanted = 100, separatorWanted = 10, suffixWanted = 200, minPrefix = 40))
    }
}
