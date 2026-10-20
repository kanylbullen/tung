package io.github.kanylbullen.tung.core.sync

/**
 * What the device has for one id: a row with its `updatedAt`, or a tombstone
 * ([deleted]) with its `deletedAt` as [updatedAt].
 */
data class LocalVersion(val updatedAt: Long, val deleted: Boolean = false)

enum class SyncAction { Insert, Update, Delete, Ignore }

data class SyncDecision(val entry: SyncEntry, val action: SyncAction)

/**
 * How a server response merges into the device: strictly newer wins, the
 * same rule the server applies. Pure; the app reads the local versions and
 * carries the decisions out in one Room transaction.
 */
object SyncApplier {

    /**
     * The decision for every row of [response], parents first ([SyncTable]
     * order). [local] gives the device's version of a row, or null when the
     * device has never had it.
     *
     * Apply the inserts and updates in list order and the deletes in reverse,
     * so a parent exists before its children arrive and children go before
     * their parent.
     */
    fun decide(response: SyncResponse, local: (SyncTable, String) -> LocalVersion?): List<SyncDecision> =
        response.changes.entries().map { entry -> SyncDecision(entry, decide(entry, local(entry.table, entry.id))) }

    /**
     * - Nothing local: a row is inserted; a tombstone has nothing to delete.
     * - A local row: the incoming one wins only when strictly newer, an update
     *   or, for a tombstone, a delete. Equal or older is ignored: a local edit
     *   that is newer (still dirty) is kept and goes out with the next push,
     *   and an equal one is the device's own row echoed back.
     * - A local tombstone: a strictly newer row brings it back (insert); an
     *   older row, or any tombstone, changes nothing.
     */
    fun decide(incoming: SyncEntry, local: LocalVersion?): SyncAction = when {
        local == null -> if (incoming.isTombstone) SyncAction.Ignore else SyncAction.Insert
        incoming.updatedAt <= local.updatedAt -> SyncAction.Ignore
        local.deleted -> if (incoming.isTombstone) SyncAction.Ignore else SyncAction.Insert
        incoming.isTombstone -> SyncAction.Delete
        else -> SyncAction.Update
    }
}
