package com.hanzidictionary.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every practice-session state transition, driven without Compose. */
class PracticeStateTest {

    private fun entry(id: String) = DeckEntry(StudyItem.character(id), null, DeckBucket.NEW)
    private fun ids(state: PracticeState) = state.deck.map { it.item.itemId }

    @Test
    fun revealFlipsTheCurrentCard() {
        assertTrue(PracticeState(listOf(entry("a"))).reveal().revealed)
    }

    @Test
    fun revealDoesNothingOnAnEmptyDeck() {
        assertFalse(PracticeState(emptyList()).reveal().revealed)
    }

    @Test
    fun collapseHidesTheAnswerWithoutMoving() {
        val next = PracticeState(listOf(entry("a"), entry("b")), revealed = true).collapse()
        assertFalse(next.revealed)
        assertEquals("a", next.current?.item?.itemId, "collapse must not advance")
    }

    @Test
    fun advanceWithRequeueSendsTheCardToTheBackFaceDown() {
        val next = PracticeState(listOf(entry("a"), entry("b"), entry("c")), revealed = true)
            .advance(requeue = true)
        assertEquals(listOf("b", "c", "a"), ids(next))
        assertEquals("b", next.current?.item?.itemId)
        assertFalse(next.revealed, "the next card must be face-down")
        assertEquals(1, next.dealt)
    }

    @Test
    fun advanceWithoutRequeueDropsTheCard() {
        val next = PracticeState(listOf(entry("a"), entry("b"), entry("c"))).advance(requeue = false)
        assertEquals(listOf("b", "c"), ids(next))
        assertEquals("b", next.current?.item?.itemId)
    }

    @Test
    fun advancingPastTheLastCardWrapsToTheStart() {
        val next = PracticeState(listOf(entry("a"), entry("b")), index = 1).advance(requeue = false)
        assertEquals(listOf("a"), ids(next))
        assertEquals(0, next.index)
        assertEquals("a", next.current?.item?.itemId)
    }

    @Test
    fun aOneCardDeckRequeuesOntoItselfAndBumpsDealt() {
        val next = PracticeState(listOf(entry("a")), revealed = true).advance(requeue = true)
        assertEquals("a", next.current?.item?.itemId)
        assertFalse(next.revealed)
        assertEquals(1, next.dealt, "dealt must bump so the card animation re-keys onto itself")
    }

    @Test
    fun missingTheLastCardKeepsItOnScreenRequeued() {
        // A miss on the final card: it re-queues to the back, index holds, so it stays up.
        val next = PracticeState(listOf(entry("a"), entry("b")), index = 1).advance(requeue = true)
        assertEquals(listOf("a", "b"), ids(next))
        assertEquals("b", next.current?.item?.itemId)
        assertEquals(1, next.dealt)
    }

    @Test
    fun advancingAnEmptyDeckIsANoOpButStaysCollapsed() {
        val next = PracticeState(emptyList(), revealed = true).advance(requeue = true)
        assertNull(next.current)
        assertFalse(next.revealed)
    }
}
