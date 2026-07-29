package com.hanzidictionary.core

/**
 * The in-session state of a practice run, pulled out of the Compose screen so every
 * transition — reveal, hide, advance, requeue — is a pure function unit tests can drive
 * without a device. The screen holds one of these in a `mutableStateOf` and reassigns it;
 * the FSRS grade / archive writes are side effects the screen does *alongside* [advance].
 *
 * [dealt] counts advances. The card view keys its throw animation on it so a card that
 * re-queues onto itself (a one-card deck, a miss on the last card) still resets to centre
 * instead of being stranded where it was thrown.
 */
data class PracticeState(
    val deck: List<DeckEntry>,
    val index: Int = 0,
    val revealed: Boolean = false,
    val dealt: Int = 0,
) {
    /** The card on screen, or null once the deck is spent. */
    val current: DeckEntry? get() = deck.getOrNull(index)

    /** Flip the current card face-up. A no-op with nothing on screen. */
    fun reveal(): PracticeState = if (current == null) this else copy(revealed = true)

    /** Hide the answer again without moving on — used when the prompt direction toggles. */
    fun collapse(): PracticeState = copy(revealed = false)

    /**
     * Drop the current card and move to the next. [requeue] sends it to the back of the
     * session instead (a miss, or a skip) so it comes round again; a recalled or archived
     * card leaves for good. Either way the next card is **face-down** — [revealed] resets,
     * so an answer never carries over to a different card.
     */
    fun advance(requeue: Boolean): PracticeState {
        val entry = current ?: return copy(revealed = false)
        val rest = deck.filterIndexed { i, _ -> i != index }
        val newDeck = if (requeue) rest + entry else rest
        val newIndex = if (index >= newDeck.size) 0 else index
        return copy(deck = newDeck, index = newIndex, revealed = false, dealt = dealt + 1)
    }
}

/**
 * The card a surface is showing, and whether it is face-up — enough to put it back on screen
 * after the deck behind it is rebuilt. See [deckOpenedAt].
 */
data class CardPin(val itemId: String, val revealed: Boolean)

/**
 * A deck opened at [pin]'s card rather than at the top.
 *
 * A deck is rebuilt far more often than a card is finished: the home feed reloads, a list
 * changes, the screen is disposed and recomposed on the way back from a character page.
 * Dealing the top card on every rebuild meant that looking up a card's *own* detail page —
 * the obvious thing to do with a card you don't know — made it disappear, and you came back
 * to a different word. Reopening at the pinned card makes the rebuild invisible: the card on
 * screen then only ever changes when the user grades, skips or archives it.
 *
 * If that card has since left the deck (graded, archived, no longer due) there is nothing to
 * preserve, and the deck opens at the top with [fallbackRevealed].
 */
fun deckOpenedAt(
    deck: List<DeckEntry>,
    pin: CardPin?,
    fallbackRevealed: Boolean = false,
): PracticeState {
    val at = if (pin == null) -1 else deck.indexOfFirst { it.item.itemId == pin.itemId }
    return if (at >= 0) PracticeState(deck, index = at, revealed = pin!!.revealed)
    else PracticeState(deck, revealed = fallbackRevealed)
}
