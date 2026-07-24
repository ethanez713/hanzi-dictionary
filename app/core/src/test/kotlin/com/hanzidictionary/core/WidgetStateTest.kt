package com.hanzidictionary.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every widget state transition, driven without a device (the provider owns only prefs +
 * RemoteViews now; this is the whole state machine). */
class WidgetStateTest {

    private fun newEntry(id: String) = DeckEntry(StudyItem.character(id), null, DeckBucket.NEW)
    private fun dueEntry(id: String) =
        DeckEntry(StudyItem.character(id), CardState(itemId = id, kind = "character", due = 0L), DeckBucket.DUE)

    /** Front/answer derived from the id so a test can tell which card was chosen. */
    private val summarize: (StudyItem) -> WidgetCardInfo? = { item ->
        WidgetCardInfo(item, front = "F:${item.itemId}", answer = "A:${item.itemId}")
    }

    private fun card(id: String) = WidgetCardInfo(StudyItem.character(id), "F:$id", "A:$id")

    @Test
    fun flipRevealsTheCurrentCard() {
        val next = nextWidgetState(WidgetState(card("a")), WidgetEvent.Flip, emptyList(), summarize)
        assertTrue(next.revealed)
        assertEquals("a", next.card?.item?.itemId)
    }

    @Test
    fun flipOnAnEmptyWidgetDoesNothing() {
        val next = nextWidgetState(WidgetState(null), WidgetEvent.Flip, emptyList(), summarize)
        assertNull(next.card)
        assertFalse(next.revealed)
    }

    /** The reported bug: after grading, the next word comes up face-down and the detail line
     * must NOT still show the previous word's definition. */
    @Test
    fun gradingResetsToTheUnexpandedNextWord() {
        val prev = WidgetState(card("a"), revealed = true)
        val next = nextWidgetState(prev, WidgetEvent.Grade, listOf(dueEntry("a"), dueEntry("b")), summarize, Random(1))

        assertFalse(next.revealed, "the next card must come up face-down")
        assertEquals("b", next.card?.item?.itemId, "must advance off the just-graded card")
        val view = widgetView(next)
        assertEquals("Tap to reveal", view.detail)
        assertFalse(view.detail.contains("A:a"), "the previous word's definition must be gone")
    }

    @Test
    fun gradingPrefersANeverSeenCard() {
        val prev = WidgetState(card("a"), revealed = true)
        // b is a due review, c has never been seen — c wins so its first signal gets captured.
        val next = nextWidgetState(prev, WidgetEvent.Grade, listOf(dueEntry("b"), newEntry("c")), summarize, Random(42))
        assertEquals("c", next.card?.item?.itemId)
    }

    @Test
    fun gradingNeverRedrawsTheCardUnderYourThumb() {
        val prev = WidgetState(card("a"), revealed = true)
        val deck = listOf(dueEntry("a"), dueEntry("b"), dueEntry("c"))
        for (seed in 0L until 20L) {
            val next = nextWidgetState(prev, WidgetEvent.Grade, deck, summarize, Random(seed))
            assertTrue(next.card?.item?.itemId != "a", "the graded card came straight back (seed $seed)")
        }
    }

    @Test
    fun gradingAnEmptiedDeckClearsTheWidget() {
        val next = nextWidgetState(WidgetState(card("a"), revealed = true), WidgetEvent.Grade, emptyList(), summarize)
        assertNull(next.card)
        val view = widgetView(next)
        assertEquals(WIDGET_EMPTY_GLYPH, view.glyph)
        assertEquals("Tap to open", view.detail)
        assertTrue(view.cardTapOpensApp)
    }

    @Test
    fun aPlainRedrawKeepsTheCurrentCardAndItsReveal() {
        val prev = WidgetState(card("a"), revealed = true)
        val next = nextWidgetState(prev, WidgetEvent.Update, listOf(dueEntry("a"), dueEntry("b")), summarize, Random(0))
        assertEquals("a", next.card?.item?.itemId, "a plain redraw must not shuffle the card away")
        assertTrue(next.revealed, "a plain redraw must not collapse a flipped card")
    }

    @Test
    fun aRedrawPicksAFreshFaceDownCardWhenTheCurrentIsGone() {
        val prev = WidgetState(card("a"), revealed = true) // 'a' was archived out of the deck
        val next = nextWidgetState(prev, WidgetEvent.Update, listOf(dueEntry("b")), summarize, Random(0))
        assertEquals("b", next.card?.item?.itemId)
        assertFalse(next.revealed)
    }

    @Test
    fun aRedrawFromEmptyPicksACard() {
        val next = nextWidgetState(WidgetState(null), WidgetEvent.Update, listOf(newEntry("a")), summarize)
        assertEquals("a", next.card?.item?.itemId)
        assertFalse(next.revealed)
    }

    @Test
    fun widgetViewRendersEveryState() {
        val faceDown = widgetView(WidgetState(card("a"), revealed = false))
        assertEquals("F:a", faceDown.glyph)
        assertEquals("Tap to reveal", faceDown.detail)
        assertFalse(faceDown.cardTapOpensApp, "a face-down card flips on tap")

        val flipped = widgetView(WidgetState(card("a"), revealed = true))
        assertEquals("F:a", flipped.glyph)
        assertEquals("A:a", flipped.detail)
        assertTrue(flipped.cardTapOpensApp, "a revealed card opens the app on tap")

        val empty = widgetView(WidgetState(null))
        assertEquals(WIDGET_EMPTY_GLYPH, empty.glyph)
        assertEquals("Tap to open", empty.detail)
        assertTrue(empty.cardTapOpensApp)
    }
}
