package com.hanzidictionary.core

import kotlin.random.Random

/**
 * The home-screen widget's state machine, pulled out of the [AppWidgetProvider] so every
 * transition is a pure function a unit test can drive without a device. The provider owns
 * the Android side (SharedPreferences, RemoteViews, the actual FSRS grade write); this file
 * owns *what card is shown and whether it's flipped*.
 *
 * The rule that fixes the reported bug lives in [nextWidgetState] and [widgetView]: any time
 * the card changes — a grade, or a redraw that lands on a different card — the next card
 * comes up **face-down**, and a face-down card draws "Tap to reveal", never the previous
 * word's definition. (The old one-card-late "echo" of the last answer is gone: once the card
 * can flip in place, echoing the previous answer just read as a stale definition.)
 */

/** A resolved widget card: the item plus the two lines the widget draws. */
data class WidgetCardInfo(val item: StudyItem, val front: String, val answer: String)

/** Everything the widget persists between taps. `card == null` means the deck is empty. */
data class WidgetState(val card: WidgetCardInfo?, val revealed: Boolean = false)

/** What the widget actually draws, derived purely from a [WidgetState]. */
data class WidgetView(
    /** The big glyph line. */
    val glyph: String,
    /** The line under it: the answer when flipped, otherwise a prompt. */
    val detail: String,
    /** true → a middle tap opens the app; false → it flips the card in place. */
    val cardTapOpensApp: Boolean,
)

/** The three things a widget tap can do. Grading's FSRS write is the provider's job — the
 * state machine only advances the card. */
sealed interface WidgetEvent {
    /** A plain redraw (onUpdate, or an app poke): keep the current card if it's still live. */
    data object Update : WidgetEvent

    /** Reveal the current card in place. */
    data object Flip : WidgetEvent

    /** Graded on a gutter: move on to the next card. */
    data object Grade : WidgetEvent
}

/** Glyph shown when there's nothing to practice. */
const val WIDGET_EMPTY_GLYPH = "字"

/**
 * Picks the widget's card the way the practice screen orders its deck. On a plain redraw
 * [keepId] holds the current card in place so it can't shuffle out from under a thumb;
 * otherwise a never-seen card (`card == null`) wins — a first look is the only way to gain
 * any signal on it — and failing that a *random* entry, so the widget doesn't march the same
 * fixed order every grade. [avoid] is the card just graded, kept off the next draw.
 */
fun selectWidgetCard(
    deck: List<DeckEntry>,
    avoid: String? = null,
    keepId: String? = null,
    random: Random = Random.Default,
): DeckEntry? {
    deck.firstOrNull { keepId != null && it.item.itemId == keepId }?.let { return it }
    val available = deck.filterNot { it.item.itemId == avoid }
    return available.firstOrNull { it.card == null }
        ?: available.randomOrNull(random)
        ?: deck.firstOrNull()
}

/**
 * Advances the stored [WidgetState] for [event] against the current [deck]. Grading and any
 * change of card land **face-down** — the reveal never carries onto the next word. A plain
 * [WidgetEvent.Update] that stays on the same card keeps its reveal, so an app-poked redraw
 * can't collapse a card the user just flipped.
 */
fun nextWidgetState(
    prev: WidgetState,
    event: WidgetEvent,
    deck: List<DeckEntry>,
    summarize: (StudyItem) -> WidgetCardInfo?,
    random: Random = Random.Default,
): WidgetState = when (event) {
    WidgetEvent.Flip -> if (prev.card == null) prev else prev.copy(revealed = true)

    WidgetEvent.Update -> {
        val picked = selectWidgetCard(deck, keepId = prev.card?.item?.itemId, random = random)
        when {
            picked == null -> WidgetState(null)
            picked.item.itemId == prev.card?.item?.itemId -> prev // same card: keep the flip
            else -> WidgetState(summarize(picked.item), revealed = false)
        }
    }

    WidgetEvent.Grade -> {
        val picked = selectWidgetCard(deck, avoid = prev.card?.item?.itemId, random = random)
        WidgetState(picked?.let { summarize(it.item) }, revealed = false)
    }
}

/** Derives the drawn lines from [state]. A face-down card shows "Tap to reveal" — never a
 * leftover definition from the card before it. */
fun widgetView(state: WidgetState): WidgetView = when {
    state.card == null -> WidgetView(WIDGET_EMPTY_GLYPH, "Tap to open", cardTapOpensApp = true)
    state.revealed -> WidgetView(state.card.front, state.card.answer, cardTapOpensApp = true)
    else -> WidgetView(state.card.front, "Tap to reveal", cardTapOpensApp = false)
}
