package com.hanzidictionary.core

import app.cash.sqldelight.db.SqlDriver
import com.hanzidictionary.core.userdb.UserDatabase

/**
 * The user's own state — which characters they know, lookup/confusion history — in a
 * separate, writable, app-owned database (spec §7). The "known characters" set is what
 * powers the app's core differentiator: connections filtered/ordered by what the user
 * already knows (spec §1). SRS review scheduling is deferred (spec §14); the status
 * column reserves room for it.
 */
class UserRepository(driver: SqlDriver) {
    private val db = UserDatabase(driver)
    private val q = db.userQueries

    fun isKnown(glyphId: String): Boolean = q.isKnown(glyphId).executeAsOne()

    fun setKnown(glyphId: String, known: Boolean) {
        if (known) q.upsertKnown(glyphId, "character", "known", now())
        else q.deleteKnown(glyphId)
    }

    fun knownIds(): Set<String> = q.allKnownIds().executeAsList().toSet()

    fun knownCount(): Long = q.knownCount().executeAsOne()

    /** Bulk import (e.g. an HSK level or a pasted list). One transaction for speed. */
    fun importKnown(glyphIds: Collection<String>) {
        val ts = now()
        q.transaction {
            for (id in glyphIds) q.upsertKnown(id, "character", "known", ts)
        }
    }

    fun logLookup(glyphId: String) = q.logLookup(glyphId, now())

    private fun now(): Long = System.currentTimeMillis()

    companion object {
        val Schema get() = UserDatabase.Schema
    }
}
