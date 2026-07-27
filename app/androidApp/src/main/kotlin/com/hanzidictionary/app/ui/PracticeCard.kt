package com.hanzidictionary.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hanzidictionary.app.audio.MandarinTts
import com.hanzidictionary.core.HanziRepository
import com.hanzidictionary.core.ItemSummary
import com.hanzidictionary.core.StudyItem

/**
 * The flashcard itself, shared by the practice screen and the home screen's due card.
 *
 * Both surfaces render *this* composable rather than a lookalike: the card, its tap-to-reveal
 * / tap-again-for-the-full-page behaviour, and its ⋮ corner are the app's flashcard idiom, and
 * a home-screen imitation that drifted from it would teach the wrong gestures.
 */

/** The back of a card: everything worth teaching once the answer is out. */
internal data class CardBack(
    val pinyin: String?,
    val gloss: String?,
    val components: List<CardComponent>,
    val wordsLabel: String,
    val words: List<BackWord>,
    val speakText: String,
)

/** One line in the card's word list — a word the character appears in, or (for a word
 * card) one of its constituent characters. */
internal data class BackWord(val text: String, val pinyin: String?, val gloss: String?)

/**
 * One part of the character, flattened for the card: the glyph, its role, and what it
 * contributes. The character page's breakdown cards carry provenance badges and drift
 * confidences too; on a flashcard that's noise — what earns its space is "氵 means water,
 * 青 sounds like qīng", because that is the mnemonic that makes the character recallable
 * rather than a shape to memorise.
 *
 * The sound line is carried whatever the part's role: a component you can *say* is a
 * component you can remember, even when it isn't the one lending the character its sound.
 * For a combining form that's its name rather than a reading — see [componentLabel].
 */
internal data class CardComponent(
    val char: String,
    val function: String,
    val label: ComponentLabel?,
    val gloss: String?,
)

internal suspend fun loadBack(repository: HanziRepository, item: StudyItem): CardBack? =
    if (item.isWord) {
        repository.wordPage(item.itemId)?.let { w ->
            CardBack(
                pinyin = w.pinyin,
                gloss = w.senses.take(3).joinToString("; "),
                // A word's parts are its characters, and they're already listed under
                // "Made of" — decomposing each of those into radicals as well would bury
                // the word itself.
                components = emptyList(),
                wordsLabel = "Made of",
                words = w.chars.map { BackWord(it.char, null, it.definition?.let(::shortGloss)) },
                speakText = w.simp,
            )
        }
    } else {
        repository.characterPage(item.itemId)?.let { c ->
            CardBack(
                pinyin = c.readings.filter { it.system == "pinyin" }
                    .distinctBy { it.value }
                    .joinToString("  ") { it.value }
                    .ifBlank { null },
                gloss = c.definition,
                components = c.breakdown.mapNotNull { b ->
                    // displayChar is null only for "ids:" pseudo-components — a dashed
                    // layout box teaches nothing on a card, so those are dropped.
                    b.displayChar?.let { ch ->
                        CardComponent(
                            ch,
                            b.function,
                            componentLabel(ch, b.pinyin),
                            b.definition?.let(::shortGloss),
                        )
                    }
                },
                // c.words is already learner-ordered (HSK, then length), so the first few
                // are the words most worth recognising this character in.
                wordsLabel = "Used in words",
                words = c.words.map { BackWord(it.simp, it.pinyin, it.gloss) },
                speakText = c.char,
            )
        }
    }

/** Components shown on the card. Each one takes an equal share of the row's width, so past
 * four they're too narrow to read — and a character with five parts is better served by the
 * full page anyway. */
private const val CARD_COMPONENTS = 4

/** Height of the card's floating ⓘ/⋮ corner — also the top inset the reverse-mode prompt
 * needs to clear it. Kept next to [CardCorner]'s button size so the two can't drift. */
private val CARD_CORNER_HEIGHT = 38.dp

/**
 * The card itself. The revealed side is three bands separated by hairlines rather than by
 * headings — **answer**, **what it's built from**, **where you'll meet it** — because a
 * label like "Components" costs a whole line to say what the row of coloured glyphs under
 * it already says. Structure carries the meaning; the ink goes to content.
 */
@Composable
internal fun PracticeCard(
    summary: ItemSummary?,
    back: CardBack?,
    reverse: Boolean,
    showAnswer: Boolean,
    tts: MandarinTts,
    onReveal: () -> Unit,
    onOpenFull: () -> Unit,
    onArchive: () -> Unit,
) {
    // One tap reveals the answer; once it's showing, the next tap opens the full page —
    // the card itself is the link, so there's no separate button to hunt for.
    Card(
        Modifier.fillMaxWidth().clickable {
            if (!showAnswer) onReveal() else onOpenFull()
        },
    ) {
        Box(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().heightIn(min = 210.dp).padding(
                    start = 14.dp,
                    end = 14.dp,
                    bottom = 14.dp,
                    // Reverse mode leads with prose, and prose is the one thing wide enough
                    // to reach the corner controls — so in that mode the stack starts below
                    // them. Character-first cards lead with a centred glyph that never gets
                    // near the corner, and they keep the tighter inset.
                    top = if (reverse) CARD_CORNER_HEIGHT else 14.dp,
                ),
                horizontalAlignment = Alignment.CenterHorizontally,
                // Face-down the glyph sits in the middle of the card, where the eye lands
                // and where a card you're about to throw belongs; revealed, the stack flows
                // from the top so the bands under it hold still as the words load in.
                verticalArrangement = Arrangement.spacedBy(
                    6.dp,
                    if (showAnswer) Alignment.Top else Alignment.CenterVertically,
                ),
            ) {
                if (summary == null) {
                    Text("…", fontSize = 80.sp)
                    return@Column
                }
                val gloss = back?.gloss ?: summary.gloss ?: "No English gloss for this."
                // Words are longer than characters, so they get a smaller face — a five-
                // character phrase at 80sp wraps into a wall.
                val faceSize = if (summary.text.length > 2) 52.sp else 80.sp

                if (!reverse) {
                    Text(summary.text, fontSize = faceSize, lineHeight = faceSize * 1.1f)
                } else {
                    Text(gloss, fontSize = 19.sp, textAlign = TextAlign.Center, lineHeight = 26.sp)
                }

                if (!showAnswer) {
                    Text(
                        "Tap to reveal",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    return@Column
                }

                if (reverse) Text(summary.text, fontSize = faceSize, lineHeight = faceSize * 1.1f)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    (back?.pinyin ?: summary.pinyin)?.let { Text(it, fontSize = 22.sp) }
                    if (tts.available) {
                        TextButton(
                            onClick = { tts.speak(back?.speakText ?: summary.text) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        ) { Text("🔊", fontSize = 16.sp) }
                    }
                }
                if (!reverse) {
                    Text(gloss, fontSize = 15.sp, textAlign = TextAlign.Center, lineHeight = 20.sp)
                }

                val components = back?.components.orEmpty()
                if (components.isNotEmpty()) {
                    CardRule()
                    ComponentStrip(components)
                }
                // Every word the pack ranks as learner-relevant for this character, not a
                // sample of them: once the card stopped spending lines on headings there was
                // room, and the whole point of the band is meeting the character in context.
                val words = back?.words.orEmpty()
                if (words.isNotEmpty()) {
                    CardRule()
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) { words.forEach { BackWordRow(it) } }
                }
            }

            // The corner controls float over the card instead of taking a row of their own:
            // in the flow they read as a toolbar and pushed the glyph — the thing you're
            // actually here to look at — a third of the way down the card. Nothing else ever
            // reaches this corner (the glyph is centred, the bands below start lower), so
            // there's nothing for them to cover.
            CardCorner(
                modifier = Modifier.align(Alignment.TopEnd),
                onArchive = onArchive,
            )
        }
    }
}

/** The hairline between two bands of the revealed card — the whole reason it needs no
 * headings. Inset from the edges so it reads as a separator, not as a border. */
@Composable
private fun CardRule() {
    HorizontalDivider(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
    )
}

/**
 * The card's action corner: a ⋮ menu holding "I know this" — the only action that is *about
 * this card*, which is why it's the only thing here. (Grading is gestures; a tappable grade
 * in this corner would reintroduce the two-input-systems problem this layout exists to
 * remove. The grading ⓘ explains the screen rather than the card, so it lives in the top bar.)
 */
@Composable
private fun CardCorner(
    modifier: Modifier = Modifier,
    onArchive: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(CARD_CORNER_HEIGHT)) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = "Card options",
                modifier = Modifier.size(19.dp),
                // Faint on purpose. The card's job is to show you one character; the menu is
                // a rarely-used escape hatch that only has to be findable once you go looking
                // for it, so it recedes rather than competing with the glyph.
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("I know this — archive it") },
                onClick = { menuOpen = false; onArchive() },
            )
        }
    }
}

/**
 * The component breakdown, compressed to fit a flashcard: one chip per part, each carrying
 * the glyph in its function colour, the role in words, and the single thing that part
 * contributes. Chips (rather than the character page's stacked full-width cards) because
 * they read as *parts of one character* sitting side by side, which is the fact being
 * taught; the colour coding is [functionColor], shared with the character page, so the two
 * read as the same thing at two levels of detail.
 */
@Composable
private fun ComponentStrip(components: List<CardComponent>) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (c in components.take(CARD_COMPONENTS)) {
            val color = functionColor(c.function)
            val label = functionLabel(c.function)
            // Equal shares, so the chips line up and a long gloss ellipsizes instead of
            // shoving its neighbour off the card.
            Card(
                Modifier.weight(1f),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            ) {
                Row(
                    Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(c.char, fontSize = 24.sp, color = color)
                    Column {
                        // The sound line rides the top of the chip whatever the part's role,
                        // but only a real reading on a sound-bearing part gets full size:
                        // everything else is a footnote — useful for saying the part out
                        // loud, not a claim about how the character is pronounced.
                        val loud = isSoundBearing(c.function) && c.label?.isReading == true
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            c.label?.let {
                                Text(
                                    it.text,
                                    fontSize = if (loud) 13.sp else 10.sp,
                                    lineHeight = 14.sp,
                                    color = if (loud) LocalContentColor.current
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            // The role is spelled out, not left to colour alone — the strip
                            // has to work for a colour-blind reader too.
                            if (label.isNotEmpty()) {
                                Text(
                                    label,
                                    fontSize = 9.sp,
                                    lineHeight = 11.sp,
                                    color = color,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        c.gloss?.let {
                            Text(
                                it,
                                fontSize = 12.sp,
                                lineHeight = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One line of the card's bottom band: the word, its sound, its meaning — the same
 * glyph-then-reading-then-gloss order as [ItemRow], so it scans like every other list of
 * words in the app. */
@Composable
private fun BackWordRow(w: BackWord) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(w.text, fontSize = 17.sp)
        w.pinyin?.let {
            Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        w.gloss?.let {
            Text(
                it,
                Modifier.weight(1f),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
