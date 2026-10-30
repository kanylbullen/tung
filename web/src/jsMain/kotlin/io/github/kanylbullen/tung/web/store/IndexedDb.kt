package io.github.kanylbullen.tung.web.store

import io.github.kanylbullen.tung.core.sync.SyncTable
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** IndexedDB refused or failed; the message is the browser's. */
class IndexedDbException(message: String?) : RuntimeException(message ?: "IndexedDB error")

/**
 * The browser's copy of the log: one object store per synced table (keyed by
 * the wire key, `exercises` … `sets`), each row one JSON value under its id
 * ([Table.encode]); `tombstones` (key `<table>:<id>`); and `meta`, small
 * settings as strings (the server, the account, the token, the cursor).
 *
 * Every transaction is issued whole and awaited once: the requests go in
 * synchronously and the coroutine resumes on `complete`, so no transaction
 * ever outlives the event-loop turn that opened it (see [TungStore]).
 * Declared with `dynamic`: the few IndexedDB calls used need no typed
 * bindings.
 */
class IndexedDbStore private constructor(private val db: dynamic) {

    /** Reads every row and tombstone into [store], in one read-only transaction. */
    suspend fun loadInto(store: TungStore) {
        val names = SyncTable.entries.map { it.key } + TOMBSTONES
        val tx = db.transaction(names.toTypedArray(), "readonly")
        val results = HashMap<String, Array<String>>()
        names.forEach { name ->
            val request = tx.objectStore(name).getAll()
            request.onsuccess = { _: dynamic -> results[name] = request.result.unsafeCast<Array<String>>() }
        }
        awaitTransaction(tx)
        SyncTable.entries.forEach { table -> results[table.key]?.forEach { store.table(table).load(it) } }
        results[TOMBSTONES]?.forEach { store.loadTombstone(it) }
    }

    /** Carries out [writes] in one read-write transaction; nothing with an empty list. */
    suspend fun write(writes: List<Write>) {
        if (writes.isEmpty()) return
        val names = writes.map { storeOf(it) }.distinct()
        val tx = db.transaction(names.toTypedArray(), "readwrite")
        writes.forEach { write ->
            val objectStore = tx.objectStore(storeOf(write))
            when (write) {
                is Write.Put -> objectStore.put(write.value, write.id)
                is Write.Delete -> objectStore.delete(write.id)
                is Write.PutTombstone -> objectStore.put(write.value, tombstoneKey(write.table, write.id))
                is Write.DeleteTombstone -> objectStore.delete(tombstoneKey(write.table, write.id))
            }
        }
        awaitTransaction(tx)
    }

    /** The meta value under [key], or null. */
    suspend fun meta(key: String): String? {
        val tx = db.transaction(arrayOf(META), "readonly")
        val request = tx.objectStore(META).get(key)
        var value: String? = null
        request.onsuccess = { _: dynamic -> value = (request.result as? String) }
        awaitTransaction(tx)
        return value
    }

    /** Stores [value] under [key], or removes the key with null. */
    suspend fun putMeta(key: String, value: String?) {
        val tx = db.transaction(arrayOf(META), "readwrite")
        val objectStore = tx.objectStore(META)
        if (value == null) objectStore.delete(key) else objectStore.put(value, key)
        awaitTransaction(tx)
    }

    fun close() {
        db.close()
    }

    private fun storeOf(write: Write): String = when (write) {
        is Write.Put -> write.table.key
        is Write.Delete -> write.table.key
        is Write.PutTombstone -> TOMBSTONES
        is Write.DeleteTombstone -> TOMBSTONES
    }

    companion object {
        const val NAME = "tung"
        const val VERSION = 1
        const val TOMBSTONES = "tombstones"
        const val META = "meta"

        private fun tombstoneKey(table: SyncTable, id: String): String = "${table.key}:$id"

        private fun indexedDb(): dynamic = js("indexedDB")

        /** Opens (and on first use creates) the database [name]. */
        suspend fun open(name: String = NAME): IndexedDbStore = suspendCancellableCoroutine { continuation ->
            val request = indexedDb().open(name, VERSION)
            request.onupgradeneeded = { _: dynamic ->
                val db = request.result
                (SyncTable.entries.map { it.key } + listOf(TOMBSTONES, META)).forEach { store ->
                    if (!(db.objectStoreNames.contains(store) as Boolean)) db.createObjectStore(store)
                }
            }
            request.onsuccess = { _: dynamic -> continuation.resume(IndexedDbStore(request.result)) }
            request.onerror = { _: dynamic -> continuation.resumeWithException(IndexedDbException(request.error?.message as? String)) }
            request.onblocked = { _: dynamic -> continuation.resumeWithException(IndexedDbException("blocked by another open page")) }
        }

        /** Deletes the database [name] (tests). */
        suspend fun delete(name: String): Unit = suspendCancellableCoroutine { continuation ->
            val request = indexedDb().deleteDatabase(name)
            request.onsuccess = { _: dynamic -> continuation.resume(Unit) }
            request.onerror = { _: dynamic -> continuation.resumeWithException(IndexedDbException(request.error?.message as? String)) }
            request.onblocked = { _: dynamic -> continuation.resumeWithException(IndexedDbException("blocked by another open page")) }
        }

        private suspend fun awaitTransaction(tx: dynamic): Unit = suspendCancellableCoroutine { continuation ->
            tx.oncomplete = { _: dynamic -> continuation.resume(Unit) }
            tx.onerror = { _: dynamic -> continuation.resumeWithException(IndexedDbException(tx.error?.message as? String)) }
            tx.onabort = { _: dynamic -> continuation.resumeWithException(IndexedDbException(tx.error?.message as? String ?: "aborted")) }
        }
    }
}
