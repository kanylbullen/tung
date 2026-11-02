package io.github.kanylbullen.tung.ui.common

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** "6 March 2023": a date with its year, for ranges such as an import's. */
fun localDateWithYear(epochMillis: Long): String =
    DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.getDefault()).format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
