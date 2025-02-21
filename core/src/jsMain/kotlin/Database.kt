package com.juul.indexeddb

import com.juul.indexeddb.external.IDBDatabase
import com.juul.indexeddb.external.IDBFactory
import com.juul.indexeddb.external.IDBVersionChangeEvent
import com.juul.indexeddb.external.indexedDB
import kotlinx.browser.window
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Inside the [initialize] block, you must not call any `suspend` functions except for:
 * - those provided by this library and scoped on [Transaction] (and its subclasses)
 * - flow operations on the flows returns by [Transaction.openCursor] and [Transaction.openKeyCursor]
 * - `suspend` functions composed entirely of other legal functions
 */
public suspend fun openDatabase(
    name: String,
    version: Int,
    initialize: suspend VersionChangeTransaction.(
        database: Database,
        oldVersion: Int,
        newVersion: Int,
    ) -> Unit,
): Database = withContext(Dispatchers.Unconfined) {
    val indexedDB: IDBFactory? = js("self.indexedDB || self.webkitIndexedDB") as? IDBFactory
    val factory = checkNotNull(indexedDB) { "Your browser doesn't support IndexedDB." }
    val request = factory.open(name, version)
    val versionChangeEvent = request.onNextEvent("success", "upgradeneeded", "error", "blocked") { event ->
        when (event.type) {
            "upgradeneeded" -> event as IDBVersionChangeEvent
            "error" -> throw ErrorEventException(event)
            "blocked" -> throw OpenBlockedException(name, event)
            else -> null
        }
    }
    Database(request.result).also { database ->
        if (versionChangeEvent != null) {
            val transaction = VersionChangeTransaction(checkNotNull(request.transaction))
            transaction.initialize(database, versionChangeEvent.oldVersion, versionChangeEvent.newVersion)
            transaction.awaitCompletion()
        }
    }
}

public suspend fun deleteDatabase(name: String) {
    val factory = checkNotNull(window.indexedDB) { "Your browser doesn't support IndexedDB." }
    val request = factory.deleteDatabase(name)
    request.onNextEvent("success", "error") { event ->
        when (event.type) {
            "error" -> throw ErrorEventException(event)
            else -> null
        }
    }
}

public class Database internal constructor(internal val database: IDBDatabase) {

    /**
     * Inside the [action] block, you must not call any `suspend` functions except for:
     * - those provided by this library and scoped on [Transaction] (and its subclasses)
     * - flow operations on the flows returns by [Transaction.openCursor] and [Transaction.openKeyCursor]
     * - `suspend` functions composed entirely of other legal functions
     */
    public suspend fun <T> transaction(
        vararg store: String,
        durability: Durability = Durability.Default,
        action: suspend Transaction.() -> T,
    ): T = withContext(Dispatchers.Unconfined) {
        val transaction = Transaction(
            database.transaction(arrayOf(*store), "readonly", transactionOptions(durability)),
        )
        val result = transaction.action()
        transaction.awaitCompletion()
        result
    }

    /**
     * Inside the [action] block, you must not call any `suspend` functions except for:
     * - those provided by this library and scoped on [Transaction] (and its subclasses)
     * - flow operations on the flows returns by [Transaction.openCursor] and [Transaction.openKeyCursor]
     * - `suspend` functions composed entirely of other legal functions
     */
    public suspend fun <T> writeTransaction(
        vararg store: String,
        durability: Durability = Durability.Default,
        action: suspend WriteTransaction.() -> T,
    ): T = withContext(Dispatchers.Unconfined) {
        val transaction = WriteTransaction(
            database.transaction(arrayOf(*store), "readwrite", transactionOptions(durability)),
        )
        with(transaction) {
            // Force overlapping transactions to not call `action` until prior transactions complete.
            objectStore(store.first())
                .openKeyCursor(autoContinue = false)
                .collect { it.close() }
        }
        val result = transaction.action()
        transaction.awaitCompletion()
        result
    }

    public fun close() {
        database.close()
    }
}

private fun transactionOptions(durability: Durability): dynamic = jso {
    this.durability = durability.jsValue
}
