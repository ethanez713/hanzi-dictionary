package com.hanzidictionary.core

import app.cash.sqldelight.db.SqlDriver
import com.hanzidictionary.core.userdb.UserDatabase
import java.util.TimeZone

/** A studiable item: a character ("U+597D") or a pack word ("w000123"). */
data class StudyItem(val itemId: String, val kind: String) {
    val isWord: Boolean get() = kind == KIND_WORD

    companion object {
        const val KIND_CHARACTER = "character"
        const val KIND_WORD = "word"

        fun character(glyphId: String) = StudyItem(glyphId, KIND_CHARACTER)
        fun word(wordId: String) = StudyItem(wordId, KIND_WORD)
    }
}

data class StudyList(val id: Long, val name: String, val createdAt: Long)

/** Counts behind each list card: what's waiting, what's in flight, what's finished. */
data class ListStats(
    val total: Int,
    val archived: Int,
    val due: Int,
    val newItems: Int,
) {
    val learning: Int get() = total - archived
}

/**
 * Answers split the way the two swipes are: right (knew) against left (missed). Counts
 * *answers*, not items — a card you miss three times is three missed answers, which is the
 * honest shape of that card's history. Built straight from a [CardState]'s `recalled`/`lapses`
 * (see the list detail screen's per-word tally), not queried independently.
 */
data class ReviewStats(val knew: Int, val missed: Int) {
    val total: Int get() = knew + missed

    /** Null when nothing was answered in the window — "0%" would be a claim, not a gap. */
    val accuracy: Double? get() = if (total == 0) null else knew.toDouble() / total
}

/** What a new-word triage swipe means (see [StudyRepository.triage]). */
enum class TriageVerdict {
    /** "I confidently know this": filed, with a first rating that schedules a real check soon. */
    KNOW,

    /** "I don't know this": filed as an ordinary new card. */
    DONT_KNOW,

    /** "Not now": not filed, and not offered again for a while. */
    SKIP,
}

/** Why a card made it into the deck — drives the "12 due · 3 ahead" header. */
enum class DeckBucket { DUE, NEW, AHEAD }

data class DeckEntry(val item: StudyItem, val card: CardState?, val bucket: DeckBucket)

/** Where a practice deck's candidates come from. Real lists and the pooled "everything"
 * resolve entirely inside this repository; the HSK/series/encountered sources need the
 * content pack, so the caller resolves those to candidate ids first. */
sealed class DeckSource {
    /** Every item in every list, pooled. */
    data object Everything : DeckSource()
    data class UserList(val listId: Long, val name: String = "") : DeckSource()
    /** Looked up or found in a photo, never filed into a list (the old implicit deck). */
    data object Encountered : DeckSource()
    data class Hsk(val level: Int) : DeckSource()
    data class Series(val componentId: String) : DeckSource()
}

/**
 * Study lists and spaced-repetition scheduling (spec §17, milestone M3.56).
 *
 * Two deliberate modelling choices carry the whole feature:
 *  - **Membership is per-list, memory is not.** [CardState] is keyed by item alone, so an
 *    item in three lists has one schedule — you don't re-learn 好 because it appears in a
 *    second list.
 *  - **Archiving is global.** "I know this" writes `known_item` (via [UserRepository]),
 *    which every deck query filters on, so archiving in one list archives everywhere.
 */
class StudyRepository(
    driver: SqlDriver,
    private val fsrs: Fsrs = Fsrs(),
) {
    private val db = UserDatabase(driver)
    private val q = db.userQueries

    fun lists(): List<StudyList> =
        q.allLists().executeAsList().map { StudyList(it.id, it.name, it.created_at) }

    fun createList(name: String, now: Long = System.currentTimeMillis()): Long {
        q.insertList(name, now)
        return q.lastListId().executeAsOne()
    }

    fun renameList(listId: Long, name: String) = q.renameList(name, listId)

    fun deleteList(listId: Long) {
        q.transaction {
            q.deleteAllItemsInList(listId)
            q.deleteList(listId)
        }
        if (activeListId() == listId) setActiveListId(null)
    }

    fun addToList(listId: Long, item: StudyItem, now: Long = System.currentTimeMillis()) =
        q.addListItem(listId, item.itemId, item.kind, now)

    fun removeFromList(listId: Long, itemId: String) = q.removeListItem(listId, itemId)

    fun listIdsContaining(itemId: String): Set<Long> =
        q.listIdsContaining(itemId).executeAsList().toSet()

    fun itemsInList(listId: Long): List<StudyItem> =
        q.itemsInList(listId).executeAsList().map { StudyItem(it.item_id, it.kind) }

    /** When each item was filed into the list, for the "date added" sort. */
    fun addedAtInList(listId: Long): Map<String, Long> =
        q.itemsInList(listId).executeAsList().associate { it.item_id to it.added_at }

    fun allListItems(): List<StudyItem> =
        q.itemsInAllLists().executeAsList().map { StudyItem(it.item_id, it.kind) }

    /** Per-list counts for every list in one pass (the lists screen renders them all). */
    fun statsByList(now: Long = System.currentTimeMillis()): Map<Long, ListStats> {
        val known = q.allKnownIds().executeAsList().toSet()
        val cards = q.allCardStates().executeAsList().associateBy { it.item_id }
        return q.allListItems().executeAsList()
            .groupBy { it.list_id }
            .mapValues { (_, rows) ->
                val live = rows.filter { it.item_id !in known }
                ListStats(
                    total = rows.size,
                    archived = rows.size - live.size,
                    due = live.count { row ->
                        cards[row.item_id]?.let { it.due <= now } ?: false
                    },
                    newItems = live.count { it.item_id !in cards },
                )
            }
    }

    // ---- the active list (swipe target, and what the home-screen widget follows) ----

    /** null means "everything pooled". */
    fun activeListId(): Long? =
        q.getPref(PREF_ACTIVE_LIST).executeAsOneOrNull()
            ?.takeIf { it != ACTIVE_EVERYTHING }
            ?.toLongOrNull()
            // A list deleted elsewhere must not leave the app pointing at a ghost.
            ?.takeIf { id -> q.listById(id).executeAsOneOrNull() != null }

    fun setActiveListId(listId: Long?) =
        q.setPref(PREF_ACTIVE_LIST, listId?.toString() ?: ACTIVE_EVERYTHING)

    /** The list a swipe-right should file into, creating the default one on first use so
     * the gesture always has somewhere to go. */
    fun ensureActiveList(now: Long = System.currentTimeMillis()): StudyList {
        activeListId()?.let { id ->
            q.listById(id).executeAsOneOrNull()?.let { return StudyList(it.id, it.name, it.created_at) }
        }
        val existing = lists().firstOrNull()
        if (existing != null) {
            setActiveListId(existing.id)
            return existing
        }
        val id = createList(DEFAULT_LIST_NAME, now)
        setActiveListId(id)
        return StudyList(id, DEFAULT_LIST_NAME, now)
    }

    fun cardState(itemId: String): CardState? =
        q.cardState(itemId).executeAsOneOrNull()?.toCardState()

    fun cardStates(itemIds: Collection<String>): Map<String, CardState> {
        if (itemIds.isEmpty()) return emptyMap()
        return q.cardStatesFor(itemIds).executeAsList().associate { it.item_id to it.toCardState() }
    }

    fun retrievability(card: CardState, now: Long = System.currentTimeMillis()): Double =
        fsrs.retrievability(card, now)

    /**
     * Records a grading and reschedules the item. [origin] distinguishes app reviews from
     * widget taps in `review_log`, which is the raw material for optimising the FSRS
     * parameters against this user's own history later. [revealMs] is how long the card sat
     * face-down before the reveal, passed only by the practice screen — a widget or
     * notification card may have sat unseen for hours, so those leave it null.
     */
    fun grade(
        item: StudyItem,
        rating: Rating,
        origin: String = ORIGIN_APP,
        now: Long = System.currentTimeMillis(),
        revealMs: Long? = null,
    ): CardState {
        val before = cardState(item.itemId) ?: CardState(item.itemId, item.kind, due = now)
        val elapsedDays = before.lastReview?.let { ((now - it) / DAY_MS).toDouble() }
        val after = fsrs.review(before, rating, now)
        q.transaction {
            q.upsertCardState(
                after.itemId,
                after.kind,
                after.phase.name.lowercase(),
                after.step?.toLong(),
                after.stability,
                after.difficulty,
                after.due,
                after.lastReview,
                after.reviews.toLong(),
                after.lapses.toLong(),
            )
            q.logReview(
                item.itemId,
                rating.value.toLong(),
                now,
                elapsedDays,
                after.stability,
                after.difficulty,
                origin,
                revealMs,
            )
        }
        return after
    }

    /** Past gradings of [itemIds], oldest first, for [masteryOf]. */
    fun reviewHistory(itemIds: Collection<String>): Map<String, List<ReviewEntry>> {
        if (itemIds.isEmpty()) return emptyMap()
        val out = HashMap<String, MutableList<ReviewEntry>>()
        // Chunked: SQLite caps bound variables, and a big list holds hundreds of items.
        for (chunk in itemIds.distinct().chunked(HISTORY_CHUNK)) {
            for (row in q.reviewHistory(chunk).executeAsList()) {
                out.getOrPut(row.item_id) { mutableListOf() } +=
                    ReviewEntry(row.ts, row.rating.toInt(), row.elapsed_days, row.origin)
            }
        }
        return out
    }

    /** [Mastery] for each item that has a schedule; items without one are simply absent. */
    fun masteryFor(itemIds: Collection<String>): Map<String, Mastery> {
        val cards = cardStates(itemIds)
        if (cards.isEmpty()) return emptyMap()
        val history = reviewHistory(cards.keys)
        return cards.mapValues { (id, card) -> masteryOf(card, history[id].orEmpty()) }
    }

    /**
     * Orders candidates into a practice deck. The policy, in priority order:
     *
     *  1. **New** — never graded, shown FIRST and given a reserved place in the deck. A
     *     first exposure is the only way to gain *any* signal on a word (there's no memory
     *     state, so nothing to schedule), so a brand-new card is never crowded out by a
     *     pile of due reviews. Capped per day ([NEW_PER_DAY]) so importing a 600-character
     *     list can't bury you — and that cap is exactly what keeps leading with new cards
     *     from turning into review debt.
     *  2. **Due** — scheduled for now or earlier, *least likely to be recalled first*.
     *  3. **Ahead** — nothing left that's due, so top up with whatever you're closest to
     *     forgetting. This is what keeps the deck (and the widget) from ever being empty.
     *
     * The moment a new card is graded even once it has a memory state (stability +
     * difficulty) and rejoins the retrievability-sorted Due/Ahead order — "most unknown
     * until it has signal, scheduled by recall after". Steps 2–3 are the principled version
     * of "show unknown cards more often": rather than a fixed 3:1 ratio, cards surface in
     * ascending order of predicted recall, which also means a session you don't finish
     * still covered the most at-risk reviews. Among new cards there's no recall signal to
     * sort by, so *which* enter today is the caller's priority (frequency, list order) and
     * their sequence is [shuffled] so the deck doesn't drill the same order every session.
     */
    fun buildDeck(
        candidates: List<StudyItem>,
        limit: Int = 60,
        now: Long = System.currentTimeMillis(),
    ): List<DeckEntry> {
        if (candidates.isEmpty()) return emptyList()
        val known = q.allKnownIds().executeAsList().toSet()
        val live = candidates.filter { it.itemId !in known }
        if (live.isEmpty()) return emptyList()
        val cards = cardStates(live.map { it.itemId })

        val due = mutableListOf<DeckEntry>()
        val fresh = mutableListOf<DeckEntry>()
        val ahead = mutableListOf<DeckEntry>()
        for (item in live) {
            val card = cards[item.itemId]
            when {
                card == null -> fresh += DeckEntry(item, null, DeckBucket.NEW)
                card.due <= now -> due += DeckEntry(item, card, DeckBucket.DUE)
                else -> ahead += DeckEntry(item, card, DeckBucket.AHEAD)
            }
        }
        val byRecall = compareBy<DeckEntry> { fsrs.retrievability(it.card!!, now) }
            .thenBy { it.card!!.due }
        due.sortWith(byRecall)
        ahead.sortWith(byRecall)

        val newCards = fresh.take(newAllowance(now)).shuffled()
        return (newCards + due + ahead).take(limit)
    }

    fun deckFor(source: DeckSource, limit: Int = 60, now: Long = System.currentTimeMillis()): List<DeckEntry> =
        when (source) {
            is DeckSource.Everything -> buildDeck(allListItems(), limit, now)
            is DeckSource.UserList -> buildDeck(itemsInList(source.listId), limit, now)
            // Pack-backed sources are resolved by the caller, which owns the pack.
            else -> emptyList()
        }

    /** Cards left in today's new-item budget. */
    fun newAllowance(now: Long = System.currentTimeMillis()): Int {
        val introduced = q.newIntroducedSince(startOfDay(now)).executeAsOne()
        return (NEW_PER_DAY - introduced).coerceAtLeast(0L).toInt()
    }

    fun reviewsToday(now: Long = System.currentTimeMillis()): Long =
        q.reviewCountSince(startOfDay(now)).executeAsOne()

    /** When the newest grading happened, whatever surface it came from; null if never. */
    fun lastReviewAt(): Long? = q.lastReviewTs().executeAsOne().MAX

    /** Distinct list items whose card is due now and not archived — the "M due" pulse on
     * the Study screen. Counts each item once even if it's filed in several lists. */
    fun dueNow(now: Long = System.currentTimeMillis()): Int {
        val known = q.allKnownIds().executeAsList().toSet()
        val itemIds = allListItems().map { it.itemId }.toSet()
        val cards = cardStates(itemIds)
        return itemIds.count { it !in known && (cards[it]?.due ?: Long.MAX_VALUE) <= now }
    }

    // ---- new-word triage ----

    /**
     * What the deck looks like to the thin-deck rule ([newWordGap]): upcoming work in the next
     * [DECK_LOOKAHEAD_DAYS] days (due, or never seen — an unseen card is a sitting's worth of
     * work too), the overdue backlog, and how well the last few real reviews went.
     */
    fun deckLoad(now: Long = System.currentTimeMillis()): DeckLoad {
        val known = q.allKnownIds().executeAsList().toSet()
        val itemIds = allListItems().map { it.itemId }.filter { it !in known }.toSet()
        val cards = cardStates(itemIds)
        val horizon = now + DECK_LOOKAHEAD_DAYS * DAY_MS
        var upcoming = 0
        var backlog = 0
        for (id in itemIds) {
            val card = cards[id]
            if (card == null) {
                upcoming++
                continue
            }
            if (card.due <= horizon) upcoming++
            if (card.due <= now) backlog++
        }
        val ratings = q.recentRatings(RECENT_SUCCESS_WINDOW.toLong()).executeAsList().map { it.toInt() }
        return DeckLoad(upcoming, backlog, recentSuccessRate(ratings))
    }

    /** How many new words the app may suggest right now; 0 unless the deck is thin. */
    fun newWordsWanted(now: Long = System.currentTimeMillis()): Int = newWordGap(deckLoad(now))

    /** Everything the user has already placed somewhere — in a list, archived, or scheduled —
     * and so should never be offered as "new". */
    fun everSeenIds(): Set<String> =
        allListItems().mapTo(HashSet()) { it.itemId } +
            q.allKnownIds().executeAsList() +
            q.allCardItemIds().executeAsList()

    /** Words swiped past recently ("not now"); they drop out of the offer for
     * [TRIAGE_SKIP_TTL_DAYS] so one skipped word can't block every later session. */
    fun recentlySkippedIds(now: Long = System.currentTimeMillis()): Set<String> {
        val cutoff = now - TRIAGE_SKIP_TTL_DAYS * DAY_MS
        return q.triageSkips().executeAsList()
            .filter { (it.value_.toLongOrNull() ?: 0L) >= cutoff }
            .mapTo(HashSet()) { it.key.removePrefix(PREF_TRIAGE_SKIP) }
    }

    /**
     * Applies a swipe on a suggested new word. Nothing here decides whether the user *really*
     * knows it — they say so, the app takes their word, and keeps the claim honest by how it
     * schedules it:
     *
     *  - [TriageVerdict.KNOW] files the word and records an Easy first rating. FSRS-6 reads that
     *    as a high initial stability (~8 days), so the word comes back for a genuine recall
     *    check in about a week instead of being trusted outright; self-rated knowledge runs high
     *    (yes/no vocabulary tests see 5-25% false alarms), and recognising a word is easier than
     *    recalling it. The grade is logged with [ORIGIN_TRIAGE], which mastery ignores.
     *  - [TriageVerdict.DONT_KNOW] files it as an ordinary never-seen card.
     *  - [TriageVerdict.SKIP] files nothing and hides it for a while.
     */
    fun triage(
        item: StudyItem,
        verdict: TriageVerdict,
        now: Long = System.currentTimeMillis(),
    ) {
        when (verdict) {
            TriageVerdict.SKIP -> q.setPref("$PREF_TRIAGE_SKIP${item.itemId}", now.toString())
            TriageVerdict.DONT_KNOW -> addToList(ensureActiveList(now).id, item, now)
            TriageVerdict.KNOW -> {
                addToList(ensureActiveList(now).id, item, now)
                grade(item, Rating.EASY, ORIGIN_TRIAGE, now)
            }
        }
    }

    /** Local midnight. Computed from the raw offset rather than java.time so this module
     * stays as close to plain Kotlin as the rest of `core`. */
    private fun startOfDay(now: Long): Long {
        val offset = TimeZone.getDefault().getOffset(now)
        return ((now + offset) / DAY_MS) * DAY_MS - offset
    }

    private fun com.hanzidictionary.core.userdb.Card_state.toCardState() = CardState(
        itemId = item_id,
        kind = kind,
        phase = CardPhase.valueOf(state.uppercase()),
        step = step?.toInt(),
        stability = stability,
        difficulty = difficulty,
        due = due,
        lastReview = last_review,
        reviews = reviews.toInt(),
        lapses = lapses.toInt(),
    )

    companion object {
        /** Anki's default is 20/day; 15 suits a dictionary that is not primarily a
         * flashcard app, where new items arrive from lookups all day anyway. */
        const val NEW_PER_DAY = 15L
        const val DEFAULT_LIST_NAME = "Saved"
        const val ORIGIN_APP = "app"
        const val ORIGIN_WIDGET = "widget"
        const val ORIGIN_NOTIFICATION = "notification"

        /** A self-reported "I already know this" when meeting a suggested new word. It seeds
         * the schedule but is a claim, not a retrieval, so mastery and the recent-accuracy
         * gate both ignore it. */
        const val ORIGIN_TRIAGE = "triage"
        private const val HISTORY_CHUNK = 500
        private const val PREF_TRIAGE_SKIP = "triage_skip:"

        /** How long a skipped suggestion stays out of the offer. */
        const val TRIAGE_SKIP_TTL_DAYS = 14L
        private const val PREF_ACTIVE_LIST = "active_list"
        private const val ACTIVE_EVERYTHING = "everything"
    }
}
