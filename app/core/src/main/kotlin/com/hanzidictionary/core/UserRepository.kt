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

    /**
     * Archives (or un-archives) an item. Archiving is global by design — it drops the item
     * out of *every* list's deck at once (spec §17) — and it discards the FSRS schedule:
     * "I know this" is a statement that the item no longer needs reviewing, so keeping a
     * due date for it would only resurrect it. Un-archiving therefore starts it fresh.
     */
    fun setKnown(itemId: String, known: Boolean, kind: String = StudyItem.KIND_CHARACTER) {
        q.transaction {
            if (known) q.upsertKnown(itemId, kind, "known", now()) else q.deleteKnown(itemId)
            q.deleteCardState(itemId)
        }
    }

    fun knownIds(): Set<String> = q.allKnownIds().executeAsList().toSet()

    fun knownCount(): Long = q.knownCount().executeAsOne()

    /** Bulk import (e.g. an HSK level or a pasted list). One transaction for speed. */
    fun importKnown(glyphIds: Collection<String>) {
        val ts = now()
        q.transaction {
            for (id in glyphIds) {
                q.upsertKnown(id, StudyItem.KIND_CHARACTER, "known", ts)
                q.deleteCardState(id)
            }
        }
    }

    fun logLookup(glyphId: String) = q.logLookup(glyphId, now())

    /** Record that the user mixed up [glyphA] and [glyphB] in a drill — seeds future
     * confusable review (spec §7 confusion_event, §14). */
    fun logConfusion(glyphA: String, glyphB: String) = q.logConfusion(glyphA, glyphB, now())

    fun confusionCount(): Long = q.confusionCount().executeAsOne()

    // ---- photo scan state ----

    fun lastScanTimestamp(): Long = q.getLastScanTimestamp().executeAsOneOrNull() ?: 0L

    fun setLastScanTimestamp(ts: Long) = q.setLastScanTimestamp(ts)

    fun recordPhotoFind(glyphId: String, photoId: String, foundAt: Long) =
        q.insertPhotoFind(glyphId, photoId, foundAt)

    /** Distinct characters found in photos, most-recently-seen first. */
    fun recentPhotoFinds(limit: Long = 200): List<PhotoFindRow> =
        q.recentPhotoFinds(limit).executeAsList().map { PhotoFindRow(it.glyph_id, it.found_at ?: 0L) }

    /** The practice deck: characters encountered (lookups + photo finds) but not yet
     * archived as known, most recently seen first. Marking one known removes it. */
    fun practiceQueue(limit: Long = 50): List<String> =
        q.practiceQueue(limit).executeAsList().map { it.gid }

    private fun now(): Long = System.currentTimeMillis()

    companion object {
        val Schema get() = UserDatabase.Schema
    }
}
