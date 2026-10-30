package io.github.kanylbullen.tung.core.importing

/**
 * One table inside an export section: a header row and the rows under it.
 * [get] reads a cell by column name; a missing column or a short row is "".
 */
class CsvTable(val header: List<String>, val rows: List<List<String>>) {
    private val index: Map<String, Int> = header.withIndex().associate { (i, name) -> name to i }

    fun has(column: String): Boolean = column in index

    fun get(row: List<String>, column: String): String = index[column]?.let { row.getOrNull(it) }.orEmpty()

    /** The rows as maps, for callers that read many columns. */
    fun records(): List<CsvRecord> = rows.map { CsvRecord(this, it) }
}

/** One data row of a [CsvTable]. */
class CsvRecord(private val table: CsvTable, private val cells: List<String>) {
    operator fun get(column: String): String = table.get(cells, column)
}

/**
 * A section of a multi-section export: `### NAME ####…` up to a line of
 * `#`s. A section holds one or more tables, each starting with its own header
 * row (Jefit's ROUTINES holds routines, days and routine exercises one after
 * the other), so tables are found by their header, never by line position.
 * [strayHeaders] counts data rows that repeated the header of the table they
 * were in; they are dropped.
 */
data class ExportSection(val name: String, val tables: List<CsvTable>, val strayHeaders: Int = 0) {
    /** The first table whose header has every one of [columns], or null. */
    fun table(vararg columns: String): CsvTable? = tables.firstOrNull { t -> columns.all(t::has) }

    /** Every table whose header has all of [columns] (a section can repeat a table, one per routine exercise). */
    fun tablesWith(vararg columns: String): List<CsvTable> = tables.filter { t -> columns.all(t::has) }

    /** The rows of every table with all of [columns], in file order. */
    fun records(vararg columns: String): List<CsvRecord> = tablesWith(*columns).flatMap { it.records() }
}

private val headerCell = Regex("[A-Za-z_][A-Za-z0-9_]*")

/** A row that names columns: every cell an identifier. Data rows always carry numbers (ids, times). */
private fun isHeaderRow(row: List<String>): Boolean = row.size > 1 && row.all { headerCell.matches(it.trim()) }

private val sectionStart = Regex("^###\\s+(.+?)\\s*#+\\s*$")

/**
 * Splits a multi-section CSV export ([text], UTF-8 already decoded; a BOM is
 * ignored) into its sections by name. Rows outside a section are ignored.
 * Within a section a header-shaped row starts a new table, except one equal
 * to the current table's header directly under its rows (no blank line
 * between): that is a stray header and dropped.
 */
fun splitSections(text: String): Map<String, ExportSection> {
    val sections = LinkedHashMap<String, ExportSection>()
    var name: String? = null
    var tables = mutableListOf<CsvTable>()
    var header: List<String>? = null
    var rows = mutableListOf<List<String>>()
    var stray = 0
    var afterBlank = true

    fun closeTable() {
        header?.let { tables += CsvTable(it, rows) }
        header = null
        rows = mutableListOf()
    }

    fun closeSection() {
        val current = name ?: return
        closeTable()
        sections[current] = ExportSection(current, tables, stray)
        name = null
        tables = mutableListOf()
        stray = 0
    }

    for (record in csvRecords(text.removePrefix("﻿"))) {
        val single = record.singleOrNull()?.trim()
        if (single != null) {
            val start = sectionStart.find(single)
            when {
                start != null -> {
                    closeSection()
                    name = start.groupValues[1].trim()
                    afterBlank = true
                    continue
                }
                single.isNotEmpty() && single.all { it == '#' } -> {
                    closeSection()
                    continue
                }
                single.isEmpty() -> {
                    afterBlank = true
                    continue
                }
            }
        }
        if (name == null) continue
        val current = header
        when {
            // The same header again right under data rows is a stray row; after a blank line it starts the next table.
            isHeaderRow(record) && !afterBlank && current != null && record.map(String::trim) == current -> stray++
            isHeaderRow(record) -> {
                closeTable()
                header = record.map(String::trim)
            }
            current != null -> rows += record
        }
        afterBlank = false
    }
    closeSection()
    return sections
}

/**
 * The records of a CSV text: fields separated by commas, a field in double
 * quotes may hold commas, newlines and `""` for a quote. Each line ending
 * outside quotes ends a record; an empty line is a record of one empty field.
 */
fun csvRecords(text: String): List<List<String>> {
    val records = mutableListOf<List<String>>()
    var fields = mutableListOf<String>()
    val field = StringBuilder()
    var quoted = false
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (quoted) {
            if (c == '"') {
                if (i + 1 < text.length && text[i + 1] == '"') {
                    field.append('"')
                    i++
                } else {
                    quoted = false
                }
            } else {
                field.append(c)
            }
        } else {
            when (c) {
                '"' -> quoted = true
                ',' -> {
                    fields += field.toString()
                    field.clear()
                }
                '\r' -> Unit
                '\n' -> {
                    fields += field.toString()
                    field.clear()
                    records += fields
                    fields = mutableListOf()
                }
                else -> field.append(c)
            }
        }
        i++
    }
    if (field.isNotEmpty() || fields.isNotEmpty()) {
        fields += field.toString()
        records += fields
    }
    return records
}
