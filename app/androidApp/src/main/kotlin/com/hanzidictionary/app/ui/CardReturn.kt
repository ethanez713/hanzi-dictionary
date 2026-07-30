package com.hanzidictionary.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.sp
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
 * [ReturnToCardButton] that jumps straight back to it.
 *
 * Modelled on the platform's **return-to-call** affordance, which is the same problem — a
 * task you stepped out of, one tap to step back — and shares its two rules: it is visible
 * only while the task is actually suspended, and it names the task rather than showing a bare
 * arrow. The label is the card's own glyph, so it can never be mistaken for Back.
 *
 * It clears itself the moment you are back on the card's screen, however you got there (the
 * button, Back, Home), after [CARD_RETURN_TTL_MS], and — since it lives only in composition —
 * on a full app restart.
 *
 * Reached through [LocalCardReturn] rather than threaded as parameters: every top bar in the
 * app needs to read it and two screens need to write it, and none of the screens in between
 * have anything to do with it.
 */
@Stable
class CardReturn(private val nav: NavHostController) {
    /** The card's display text, or null when no card is suspended. Drives the button. */
    var label by mutableStateOf<String?>(null)
        private set

    /** Which back-stack destination the card lives on. Held as the destination *id* because
     * the practice route is parameterised, and its filled route isn't reconstructable here. */
    private var destinationId: Int? = null

    /** Filled route to *open* the card's screen, for a card that has no screen on the stack
     * to unwind to (the widget hand-off). Null means the ordinary case: pop back to
     * [destinationId]. */
    private var openRoute: String? = null

    /** Called by a card surface as it hands off to a detail page, while it is still the
     * current destination — that is what makes [destinationId] the card's own screen. */
    fun leaving(label: String) {
        this.label = label
        destinationId = nav.currentBackStackEntry?.destination?.id
        openRoute = null
    }

    /**
     * Called when the card was handed to the app **from outside** — the widget's middle tap,
     * which opens a detail page from whatever screen the app happened to be showing (home on
     * a cold start, some character page four levels deep on a warm one). There is no screen
     * you left, so popping is meaningless: the card's screen is Practice, which resumes on
     * the pinned card, and [route] is how to open it.
     *
     * [destinationRoute] is the practice route's *pattern*, which is what the graph knows it
     * by — resolving it to an id here lets arrival clear the marker exactly like the pop case.
     */
    fun leavingFor(label: String, route: String, destinationRoute: String) {
        this.label = label
        destinationId = runCatching { nav.graph.findNode(destinationRoute)?.id }.getOrNull()
        openRoute = route
    }

    /** Returns to the card's screen. The card itself is still the one you left: the deck
     * rebuild that happens on arrival reopens on it (`deckOpenedAt`). */
    fun returnToCard() {
        val route = openRoute
        if (route != null) {
            // Not a second copy stacked on top of the browse path: unwind to home first, so
            // Back from the card leaves the same one-entry history practice normally has.
            nav.navigate(route) { popUpTo(nav.graph.startDestinationId) { inclusive = false } }
        } else {
            destinationId?.let { nav.popBackStack(it, false) }
        }
    }

    fun clear() {
        label = null
        destinationId = null
        openRoute = null
    }

    /** True once [entryDestinationId] is the card's own screen — the trail has been walked. */
    fun isAtCard(entryDestinationId: Int?): Boolean =
        destinationId != null && destinationId == entryDestinationId
}

/** Null wherever no [CardReturn] has been provided (previews, tests), which makes
 * [ReturnToCardButton] a no-op rather than a crash. */
val LocalCardReturn = compositionLocalOf<CardReturn?> { null }

/**
 * Top-bar action that returns to the suspended flashcard, and renders nothing when there
 * isn't one.
 *
 * Goes immediately **before** [HomeButton], so the two escapes read as a pair ordered by how
 * far they take you: back to the card you were working on, then out to home. It is a top-bar
 * action rather than a floating button because the character page's density target is to fit
 * definition, words and components on one screen — a FAB would sit on top of that content,
 * and Material reserves it for a screen's own primary action anyway, which this is not.
 */
@Composable
fun ReturnToCardButton() {
    val cardReturn = LocalCardReturn.current ?: return
    val label = cardReturn.label ?: return
    TextButton(onClick = { cardReturn.returnToCard() }) {
        // The glyph carries the meaning; the arrow only says which direction it is.
        Text("↩ $label", fontSize = 15.sp, color = MaterialTheme.colorScheme.primary)
    }
}
