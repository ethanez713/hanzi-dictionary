package com.hanzidictionary.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController

/** How long a suspended card is still worth offering a way back to. Past this you have long
 * since stopped browsing "from" the card, and a button promising to return you to one is a
 * promise about something you no longer remember leaving. */
const val CARD_RETURN_TTL_MS = 10 * 60 * 1000L

/**
 * The way back to the flashcard a browse path started from.
 *
 * Tapping a card twice opens its full page, which is the right thing to do with a card you
 * didn't know — and then you follow a component, and a character in that component's list,
 * and by the fourth page Back is a row of taps and Home overshoots the card entirely. This is
 * the trail marker: while a card is suspended, every dictionary screen carries a
 * [ReturnToCardFab] that jumps straight back to it.
 *
 * Modelled on the platform's **return-to-call** affordance, which is the same problem — a
 * task you stepped out of, one tap to step back — and shares its three rules: it is visible
 * only while the task is actually suspended, it names the task rather than showing a bare
 * arrow, and it is *loud*. The label is the card's own glyph, so it can never be mistaken for
 * Back.
 *
 * It clears itself the moment you are back on the card's screen, however you got there (the
 * button, Back, Home), after [CARD_RETURN_TTL_MS], and — since it lives only in composition —
 * on a full app restart or a stale-session reset ([com.hanzidictionary.core.isStaleSession]).
 *
 * Reached through [LocalCardReturn] rather than threaded as parameters: every dictionary
 * screen needs to read it and two card surfaces need to write it, and none of the screens in
 * between have anything to do with it.
 */
@Stable
class CardReturn(private val nav: NavHostController) {
    /** The card's display text, or null when no card is suspended. Drives the button. */
    var label by mutableStateOf<String?>(null)
        private set

    /** Which back-stack destination the card lives on. Held as the destination *id* because
     * the practice route is parameterised, and its filled route isn't reconstructable here. */
    private var destinationId: Int? = null

    /** Called by a card surface as it hands off to a detail page, while it is still the
     * current destination — that is what makes [destinationId] the card's own screen.
     *
     * The widget's hand-off arrives from outside the app, where there is no card screen to
     * have left; it pushes Practice onto the stack *first* and then calls this, so the marker
     * points at a real entry there too and there is only one way back to model. */
    fun leaving(label: String) {
        this.label = label
        destinationId = nav.currentBackStackEntry?.destination?.id
    }

    /** Returns to the card's screen. The card itself is still the one you left: the deck
     * rebuild that happens on arrival reopens on it (`deckOpenedAt`). */
    fun returnToCard() {
        destinationId?.let { nav.popBackStack(it, false) }
    }

    fun clear() {
        label = null
        destinationId = null
    }

    /** True once [entryDestinationId] is the card's own screen — the trail has been walked. */
    fun isAtCard(entryDestinationId: Int?): Boolean =
        destinationId != null && destinationId == entryDestinationId
}

/** Null wherever no [CardReturn] has been provided (previews, tests), which makes
 * [ReturnToCardFab] a no-op rather than a crash. */
val LocalCardReturn = compositionLocalOf<CardReturn?> { null }

/**
 * The way back to the suspended flashcard: a floating button in the bottom-right corner,
 * rendering nothing when no card is suspended.
 *
 * It began as a top-bar text action, on the theory that a FAB would cover the character
 * page's content — and in use it was simply never seen: the top bar is where you look for
 * *navigation chrome*, not for the one thing you actually want next, and a text link there
 * reads as a label. Bottom-right is where a thumb already is and where an app's live action
 * belongs, and covering a corner of the page is the cheaper cost by far.
 *
 * While it is showing it *is* the screen's primary action, which is why it takes the FAB slot
 * on pages that have no other: you are mid-review, and everything else on screen is reference
 * material you opened in service of this card.
 */
@Composable
fun ReturnToCardFab() {
    val cardReturn = LocalCardReturn.current ?: return
    val label = cardReturn.label ?: return
    ExtendedFloatingActionButton(
        onClick = { cardReturn.returnToCard() },
        icon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) },
        text = { Text(label) },
        // Not the default primary container: on the home screen this sits directly above the
        // Practice button, and two identically-coloured pills would read as one control.
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    )
}
