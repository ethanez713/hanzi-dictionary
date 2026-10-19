package com.hanzidictionary.core

import app.cash.sqldelight.db.SqlDriver
import com.hanzidictionary.core.userdb.UserDatabase
import java.util.TimeZone
import kotlin.random.Random

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
        val after = fsrs.review(before, rating, now)
        q.transaction {
            persistCard(after)
            persistReview(item.itemId, rating, before, after, origin, now, revealMs)
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
        val introduced = q.newIntroducedSince(startOfLocalDay(now)).executeAsOne()
        return (NEW_PER_DAY - introduced).coerceAtLeast(0L).toInt()
    }

    fun reviewsToday(now: Long = System.currentTimeMillis()): Long =
        q.reviewCountSince(startOfLocalDay(now)).executeAsOne()

    /**
     * Review rows since local midnight, triage excluded. The daily-goal ring counts
     * gradings; a triage "know" is a claim, so it stays out. [reviewsToday] still
     * counts every row — the Library total includes those claims. Row count, not
     * distinct items: grading the same card twice is two reviews.
     */
    fun goalReviewsToday(now: Long = System.currentTimeMillis()): Long =
        q.reviewItemsSince(startOfLocalDay(now)).executeAsList().size.toLong()

    /** Distinct items reviewed on each local day from [days] midnights ago through [now]. */
    fun activity(
        now: Long = System.currentTimeMillis(),
        days: Int,
        tz: TimeZone = TimeZone.getDefault(),
    ): Map<Long, Int> {
        // 24h steps back from local midnight, not civil midnights: the same clock as
        // the new-card budget, so a review is "today" in both places.
        val since = startOfLocalDay(now, tz) - days * DAY_MS
        val rows = q.reviewItemsSince(since).executeAsList().map { ActivityRow(it.ts, it.item_id) }
        return dailyActivity(rows, tz)
    }

    /** The streak as of [now]. A year-plus of history holds both the current run and
     * the longest one, without reading every review ever logged. */
    fun streakState(
        now: Long = System.currentTimeMillis(),
        tz: TimeZone = TimeZone.getDefault(),
    ): StreakState = streak(activity(now, STREAK_LOOKBACK_DAYS, tz).keys, localDay(now, tz))

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

    // ---- final check ----

    /**
     * How the archive row should read, and whether the study strip should offer a sitting.
     * Waiting candidates are not eligible yet; [FinalCheckStatus.daysToFirstUnlock] is the
     * soonest of them. The archive sample counts only items left untested for
     * [AUDIT_MIN_GAP_DAYS] (or never tested).
     */
    fun finalCheckStatus(
        now: Long = System.currentTimeMillis(),
        tz: TimeZone = TimeZone.getDefault(),
    ): FinalCheckStatus {
        val (gated, audit) = loadFinalCheck(now, tz)
        val days = gated
            .filter { !it.gate.eligible }
            .mapNotNull { it.gate.unlockAt }
            .minOrNull()
            ?.let { daysUntilUnlock(it, now).takeIf { d -> d > 0 } }
        return FinalCheckStatus(
            eligible = gated.count { it.gate.eligible },
            daysToFirstUnlock = days,
            auditable = audit.size,
        )
    }

    /**
     * Up to [FINAL_CHECK_LIMIT] eligible candidates, least likely to be recalled first,
     * then up to [AUDIT_SAMPLE_LIMIT] archived items weighted toward the ones memory
     * says are closest to gone. [random] draws the sample, so a test can replay it.
     */
    fun finalCheckSession(
        now: Long = System.currentTimeMillis(),
        tz: TimeZone = TimeZone.getDefault(),
        random: Random = Random.Default,
    ): List<FinalCheckCard> {
        val (gated, audit) = loadFinalCheck(now, tz)
        val eligible = gated.filter { it.gate.eligible }.map {
            FinalCheckCard(FinalCheckKind.CANDIDATE, it.item, it.mastery, it.retrievability)
        }
        val auditable = audit.map {
            FinalCheckCard(FinalCheckKind.AUDIT, it.item, Mastery.NONE, it.retrievability)
        }
        val built = buildFinalCheckSession(eligible, auditable, random)
        val sampled = built.filter { it.kind == FinalCheckKind.AUDIT }
        if (sampled.isEmpty()) return built
        val history = reviewHistory(sampled.map { it.item.itemId })
        val prospectById = audit.associateBy { it.item.itemId }
        return built.map { card ->
            if (card.kind != FinalCheckKind.AUDIT) card
            else card.copy(
                mastery = auditMastery(
                    card.item,
                    prospectById.getValue(card.item.itemId),
                    history[card.item.itemId].orEmpty(),
                    tz,
                ),
            )
        }
    }

    /**
     * Applies one swipe.
     *
     * A candidate pass grades Good and then archives. Archiving deletes card_state —
     * that stays the archive's rule — so the grade has to land in review_log first,
     * where its post-review stability is the only memory a later spot-check can rebuild.
     * A candidate miss grades Again and stays in study. Skip writes nothing.
     *
     * An archive pass logs Good against the rebuilt memory and does not resurrect
     * card_state. An archive miss un-archives, restores that memory as a review-phase
     * card, grades Again, and files the item into the active list if it is in none —
     * otherwise it would be a schedule no deck deals. [UserRepository.setKnown] can't
     * do this: un-archiving through it deletes the schedule being restored.
     *
     * Returns the undo for an archive or a return-to-study, and null otherwise.
     */
    fun answerFinalCheck(
        item: StudyItem,
        kind: FinalCheckKind,
        swipe: FinalCheckSwipe,
        now: Long = System.currentTimeMillis(),
    ): FinalCheckUndo? {
        if (swipe == FinalCheckSwipe.SKIP) return null
        return when (kind) {
            FinalCheckKind.CANDIDATE -> answerCandidate(item, swipe, now)
            FinalCheckKind.AUDIT -> answerAudit(item, swipe, now)
        }
    }

    fun undoFinalCheck(undo: FinalCheckUndo) {
        q.transaction {
            q.deleteReviewRow(undo.reviewRowId)
            if (undo.card != null) persistCard(undo.card) else q.deleteCardState(undo.item.itemId)
            if (undo.wasKnown) {
                q.upsertKnown(undo.item.itemId, undo.item.kind, "known", undo.knownAddedAt)
            } else {
                q.deleteKnown(undo.item.itemId)
            }
            undo.addedToListId?.let { q.removeListItem(it, undo.item.itemId) }
        }
    }

    private fun answerCandidate(item: StudyItem, swipe: FinalCheckSwipe, now: Long): FinalCheckUndo? {
        val before = cardState(item.itemId) ?: CardState(item.itemId, item.kind, due = now)
        val rating = if (swipe == FinalCheckSwipe.PASS) Rating.GOOD else Rating.AGAIN
        val after = fsrs.review(before, rating, now)
        if (swipe == FinalCheckSwipe.MISS) {
            q.transaction {
                persistCard(after)
                persistReview(item.itemId, rating, before, after, ORIGIN_FINAL, now, null)
            }
            return null
        }
        var rowId = 0L
        q.transaction {
            persistCard(after)
            // Read the rowid before the archive insert, or undo would delete that row
            // instead of this grade.
            rowId = persistReview(item.itemId, rating, before, after, ORIGIN_FINAL, now, null)
            q.upsertKnown(item.itemId, item.kind, "known", now)
            q.deleteCardState(item.itemId)
        }
        return FinalCheckUndo(
            item = item,
            reviewRowId = rowId,
            card = before,
            wasKnown = false,
            knownAddedAt = 0L,
            addedToListId = null,
        )
    }

    private fun answerAudit(item: StudyItem, swipe: FinalCheckSwipe, now: Long): FinalCheckUndo? {
        val base = auditBaseCard(item, now)
        val rating = if (swipe == FinalCheckSwipe.PASS) Rating.GOOD else Rating.AGAIN
        val after = fsrs.review(base, rating, now)
        if (swipe == FinalCheckSwipe.PASS) {
            // review_log only. Resurrecting card_state would put an archived item back
            // into every deck that filters on it.
            q.transaction {
                persistReview(item.itemId, rating, base, after, ORIGIN_AUDIT, now, null)
            }
            return null
        }
        val knownAddedAt = q.knownAddedAt(item.itemId).executeAsOneOrNull()
        var rowId = 0L
        var addedToList: Long? = null
        q.transaction {
            q.deleteKnown(item.itemId)
            persistCard(after)
            rowId = persistReview(item.itemId, rating, base, after, ORIGIN_AUDIT, now, null)
            if (q.listIdsContaining(item.itemId).executeAsList().isEmpty()) {
                val list = ensureActiveList(now)
                q.addListItem(list.id, item.itemId, item.kind, now)
                addedToList = list.id
            }
        }
        return FinalCheckUndo(
            item = item,
            reviewRowId = rowId,
            card = null,
            wasKnown = knownAddedAt != null,
            knownAddedAt = knownAddedAt ?: 0L,
            addedToListId = addedToList,
        )
    }

    /** No review to rebuild from means there is no review-phase memory to restore, so a
     * miss starts the item as a new card. [AUDIT_NO_HISTORY_RETRIEVABILITY] is only a sampling weight. */
    private fun auditBaseCard(item: StudyItem, now: Long): CardState {
        val newest = q.newestReviewFor(item.itemId).executeAsOneOrNull()
        val rows = reviewHistory(listOf(item.itemId))[item.itemId].orEmpty()
        val memory = rebuiltMemory(
            newest?.stability,
            newest?.difficulty,
            newest?.ts,
            reviews = rows.size,
            lapses = rows.count { it.rating == Rating.AGAIN.value },
        )
        return memory?.let { rebuiltCard(item, it) } ?: CardState(item.itemId, item.kind, due = now)
    }

    private fun auditMastery(
        item: StudyItem,
        prospect: AuditProspect,
        rows: List<ReviewEntry>,
        tz: TimeZone,
    ): Mastery {
        val memory = rebuiltMemory(
            prospect.stability,
            prospect.difficulty,
            prospect.reviewedAt,
            reviews = rows.size,
            lapses = rows.count { it.rating == Rating.AGAIN.value },
        )
        return masteryOf(memory?.let { rebuiltCard(item, it) }, rows, tz)
    }

    private fun loadFinalCheck(now: Long, tz: TimeZone): Pair<List<GatedCandidate>, List<AuditProspect>> {
        val knownRows = q.allKnown().executeAsList()
        val knownIds = knownRows.mapTo(HashSet()) { it.item_id }
        val active = allListItems().filter { it.itemId !in knownIds }
        val cards = cardStates(active.map { it.itemId })
        val promising = active.mapNotNull { item ->
            val card = cards[item.itemId] ?: return@mapNotNull null
            val stability = card.stability ?: return@mapNotNull null
            if (stability < RETIRE_MIN_STABILITY_DAYS || card.lastReview == null) return@mapNotNull null
            item to card
        }
        val history = reviewHistory(promising.map { it.first.itemId })
        val gated = promising.mapNotNull { (item, card) ->
            val mastery = masteryOf(card, history[item.itemId].orEmpty(), tz)
            if (!isFinalCheckCandidate(mastery)) return@mapNotNull null
            val last = card.lastReview ?: return@mapNotNull null
            GatedCandidate(
                item = item,
                card = card,
                mastery = mastery,
                retrievability = fsrs.retrievability(card, now),
                gate = candidateGate(mastery.longestGapDays, last, now, tz),
            )
        }

        val newest = newestByItem()
        val audit = knownRows.mapNotNull { row ->
            val item = StudyItem(row.item_id, row.kind)
            val latest = newest[row.item_id]
            if (!auditEligible(latest?.ts, now)) return@mapNotNull null
            val memory = rebuiltMemory(latest?.stability, latest?.difficulty, latest?.ts, 0, 0)
            val recall = if (memory == null) {
                AUDIT_NO_HISTORY_RETRIEVABILITY
            } else {
                fsrs.retrievability(rebuiltCard(item, memory), now)
            }
            AuditProspect(item, recall, latest?.stability, latest?.difficulty, latest?.ts)
        }
        return gated to audit
    }

    private fun newestByItem(): Map<String, NewestReview> {
        val best = HashMap<String, NewestReview>()
        for (row in q.newestReviews().executeAsList()) {
            val current = NewestReview(row.ts, row.stability, row.difficulty, row.review_rowid)
            val prev = best[row.item_id]
            if (prev == null || current.ts > prev.ts || (current.ts == prev.ts && current.rowId > prev.rowId)) {
                best[row.item_id] = current
            }
        }
        return best
    }

    private fun persistCard(card: CardState) {
        q.upsertCardState(
            card.itemId,
            card.kind,
            card.phase.name.lowercase(),
            card.step?.toLong(),
            card.stability,
            card.difficulty,
            card.due,
            card.lastReview,
            card.reviews.toLong(),
            card.lapses.toLong(),
        )
    }

    /** Returns the review_log rowid. The caller has to read it before the next insert. */
    private fun persistReview(
        itemId: String,
        rating: Rating,
        before: CardState,
        after: CardState,
        origin: String,
        now: Long,
        revealMs: Long?,
    ): Long {
        val elapsedDays = before.lastReview?.let { ((now - it) / DAY_MS).toDouble() }
        q.logReview(
            itemId,
            rating.value.toLong(),
            now,
            elapsedDays,
            after.stability,
            after.difficulty,
            origin,
            revealMs,
        )
        return q.lastInsertedRowId().executeAsOne()
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

        /** History read for [streakState]. Long enough for the current run and the longest. */
        private const val STREAK_LOOKBACK_DAYS = 400

        const val DEFAULT_LIST_NAME = "Saved"
        const val ORIGIN_APP = "app"
        const val ORIGIN_WIDGET = "widget"
        const val ORIGIN_NOTIFICATION = "notification"

        /** A self-reported "I already know this" when meeting a suggested new word. It seeds
         * the schedule but is a claim, not a retrieval, so mastery and the recent-accuracy
         * gate both ignore it. */
        const val ORIGIN_TRIAGE = "triage"

        /** A final-check retrieval of a card under consideration for the archive. */
        const val ORIGIN_FINAL = "final"

        /** An archive spot-check. A retrieval, unlike [ORIGIN_TRIAGE]: mastery and the
         * recent-accuracy gate both count it. */
        const val ORIGIN_AUDIT = "audit"
        private const val HISTORY_CHUNK = 500
        private const val PREF_TRIAGE_SKIP = "triage_skip:"

        /** How long a skipped suggestion stays out of the offer. */
        const val TRIAGE_SKIP_TTL_DAYS = 14L
        private const val PREF_ACTIVE_LIST = "active_list"
        private const val ACTIVE_EVERYTHING = "everything"
    }
}

private data class GatedCandidate(
    val item: StudyItem,
    val card: CardState,
    val mastery: Mastery,
    val retrievability: Double,
    val gate: CandidateGate,
)

private data class AuditProspect(
    val item: StudyItem,
    val retrievability: Double,
    val stability: Double?,
    val difficulty: Double?,
    val reviewedAt: Long?,
)

private data class NewestReview(
    val ts: Long,
    val stability: Double?,
    val difficulty: Double?,
    val rowId: Long,
)
