package io.github.kanylbullen.tung.core.sync

/**
 * What to push: every dirty row and every dirty tombstone, as requests of at
 * most [SYNC_MAX_ROWS] rows each.
 *
 * Rows go out parents first ([SyncTable] order) across all chunks, so a
 * child is never sent in an earlier request than its parent, and within a
 * request the parent's list comes first. When the same id is both a dirty row
 * and a tombstone (only possible if it was re-created after a delete), the
 * newer one is sent, and on a tie the tombstone.
 *
 * Every request carries [cursor]; the client replaces it with the cursor of
 * the previous response before it sends the next chunk. With nothing dirty
 * the plan is one empty request, which only pulls.
 */
object SyncPlanner {

    fun plan(
        dirty: SyncChanges,
        tombstones: SyncChanges,
        cursor: Long,
        maxRows: Int = SYNC_MAX_ROWS,
    ): List<SyncRequest> {
        require(maxRows > 0) { "maxRows must be positive" }
        val newest = LinkedHashMap<Pair<SyncTable, String>, SyncEntry>()
        (dirty.entries() + tombstones.entries()).forEach { entry ->
            val key = entry.table to entry.id
            val seen = newest[key]
            if (seen == null || wins(entry, seen)) newest[key] = entry
        }
        val ordered = newest.values.sortedBy { it.table.ordinal } // stable: keeps input order within a table
        if (ordered.isEmpty()) return listOf(SyncRequest(cursor = cursor))
        return ordered.chunked(maxRows).map { SyncRequest(cursor = cursor, changes = it.toChanges()) }
    }

    private fun wins(candidate: SyncEntry, current: SyncEntry): Boolean =
        candidate.updatedAt > current.updatedAt ||
            (candidate.updatedAt == current.updatedAt && candidate.isTombstone && !current.isTombstone)
}
