package io.github.kanylbullen.tung.core.importing

import org.junit.Assert.assertEquals
import org.junit.Test

class ExportSectionsTest {

    @Test
    fun quotedFieldsKeepCommasQuotesAndNewlines() {
        val records = csvRecords("a,\"b,c\",\"say \"\"hi\"\"\"\r\n\"two\nlines\",x\n")
        assertEquals(listOf(listOf("a", "b,c", "say \"hi\""), listOf("two\nlines", "x")), records)
    }

    @Test
    fun aSectionHoldsSeveralTablesFoundByTheirHeader() {
        val text = """
            ### ROUTINES ######
            
            row_id,_id,name
            1,10,"Push"
            
            row_id,package,_id,name
            2,10,20,"Day 1"
            
            row_id,belongplan,exercisename
            3,20,"Bench"
            
            row_id,belongplan,exercisename
            4,20,"Row"
            ######
            ### NOTES ####
            
            ######
        """.trimIndent()
        val sections = splitSections(text)
        val routines = sections.getValue("ROUTINES")
        assertEquals(4, routines.tables.size)
        assertEquals(listOf("Push"), routines.tables[0].records().map { it["name"] })
        assertEquals(listOf("Bench", "Row"), routines.records("belongplan", "exercisename").map { it["exercisename"] })
        assertEquals(listOf("Day 1"), routines.records("package").map { it["name"] })
        assertEquals(emptyList<CsvTable>(), sections.getValue("NOTES").tables)
    }

    @Test
    fun theSameHeaderDirectlyUnderDataIsAStrayRow() {
        val text = "﻿### LOGS ####\n\nid,ename\n1,\"Squat\"\nid,ename\n2,\"Bench\"\n######\n"
        val logs = splitSections(text).getValue("LOGS")
        assertEquals(1, logs.tables.size)
        assertEquals(1, logs.strayHeaders)
        assertEquals(listOf("Squat", "Bench"), logs.records("ename").map { it["ename"] })
    }
}
