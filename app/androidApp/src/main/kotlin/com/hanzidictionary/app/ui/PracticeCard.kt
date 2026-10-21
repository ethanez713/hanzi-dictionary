package com.hanzidictionary.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.hanzidictionary.core.SentenceExample
import com.hanzidictionary.core.StudyItem
import com.hanzidictionary.core.isSoundBearing

/** The back of a card: everything worth teaching once the answer is out. */
internal data class CardBack(
    val pinyin: String?,
    val gloss: String?,
    val components: List<CardComponent>,
    val wordsLabel: String,
    val words: List<BackWord>,
    val sentences: List<SentenceExample>,
    val speakText: String,
)

/** One line in the card's word list. */
internal data class BackWord(val text: String, val pinyin: String?, val gloss: String?)

/**
 * One part of the character, flattened for the card: the glyph, its role, and what it
 * contributes. The sound line is carried whatever the part's role.
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
                components = emptyList(),
                wordsLabel = "Made of",
                words = w.chars.map { BackWord(it.char, null, it.definition?.let(::shortGloss)) },
                sentences = repository.cardSentences(w.simp),
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
                    b.displayChar?.let { ch ->
                        CardComponent(
                            ch,
                            b.function,
                            componentLabel(ch, b.pinyin),
                            b.definition?.let(::shortGloss),
                        )
                    }
                },
                wordsLabel = "Used in words",
                words = repository.wordsForGlyph(item.itemId, CARD_WORDS)
                    .map { BackWord(it.simp, it.pinyin, it.gloss) },
                sentences = repository.cardSentences(c.char),
                speakText = c.char,
            )
        }
    }

private const val CARD_COMPONENTS = 4

/** Words to offer on a character card. The revealed side scrolls, so this is a ceiling on
 * how much a common character (的, 一) can pour out, not a target every card reaches. */
internal const val CARD_WORDS = 24L

/**
 * The flashcard. The glyph lives in a fixed 200dp zone and NEVER moves when the card
 * flips — revealed content expands below it. One tap reveals, a second tap opens the
 * full detail page. The ⋮ corner is the only other control; grading is gesture-only.
 */
@Composable
internal fun PracticeCard(
    summary: ItemSummary?,
    back: CardBack?,
    showAnswer: Boolean,
    tts: MandarinTts,
    onReveal: () -> Unit,
    onOpenFull: () -> Unit,
    onArchive: () -> Unit,
) {
    val eth = EthanTheme.colors
    Card(
        Modifier.fillMaxWidth().clickable {
            if (!showAnswer) onReveal() else onOpenFull()
        },
        colors = CardDefaults.cardColors(containerColor = eth.paper0),
    ) {
        Box(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                // Fixed-height glyph zone: always 200dp, glyph centred at 132sp.
                // Content below this zone expands on reveal; the glyph never shifts.
                Box(
                    Modifier.fillMaxWidth().height(200.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (summary == null) {
                        Text("…", fontFamily = NotoCjk, fontSize = 80.sp, color = eth.textFaint)
                    } else {
                        val faceSize = if (summary.text.length > 2) 52.sp else 132.sp
                        Text(
                            summary.text,
                            fontFamily = NotoCjk,
                            fontSize = faceSize,
                            lineHeight = faceSize * 1.05f,
                            color = eth.ink900,
                        )
                    }
                }

                if (!showAnswer) {
                    // Face-down hint sits below the glyph zone
                    Box(
                        Modifier.fillMaxWidth().padding(bottom = 20.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "Tap to reveal",
                            fontSize = 12.sp,
                            color = eth.textFaint,
                            fontFamily = InstrumentSans,
                        )
                    }
                } else {
                    // Revealed content: answer band, component band, words band
                    val gloss = back?.gloss ?: summary?.gloss ?: "No English gloss."

                    Column(
                        Modifier.fillMaxWidth().padding(
                            start = 14.dp, end = 14.dp, bottom = 14.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // Pinyin + TTS
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val reading = back?.pinyin ?: summary?.pinyin
                            if (reading != null) {
                                Text(
                                    reading,
                                    fontSize = 22.sp,
                                    fontFamily = PlexMono,
                                    color = eth.textBody,
                                )
                            }
                            if (tts.available) {
                                TextButton(
                                    onClick = { tts.speak(back?.speakText ?: summary?.text ?: "") },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                ) { Text("🔊", fontSize = 16.sp) }
                            }
                        }

                        Text(
                            gloss,
                            fontSize = 15.sp,
                            textAlign = TextAlign.Center,
                            lineHeight = 20.sp,
                            color = eth.textBody,
                            fontFamily = InstrumentSans,
                        )

                        val components = back?.components.orEmpty()
                        if (components.isNotEmpty()) {
                            CardRule()
                            ComponentStrip(components)
                        }

                        val words = back?.words.orEmpty()
                        if (words.isNotEmpty()) {
                            CardRule()
                            Column(
                                Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) { words.forEach { BackWordRow(it) } }
                        }

                        val sentences = back?.sentences.orEmpty()
                        if (sentences.isNotEmpty()) {
                            CardRule()
                            Column(
                                Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(7.dp),
                            ) { sentences.forEach { BackSentenceRow(it) } }
                        }
                    }
                }
            }

            // Corner menu floats over the fixed glyph zone; nothing in the zone ever
            // reaches this corner (the glyph is centred), so there's no overlap.
            CardCorner(
                modifier = Modifier.align(Alignment.TopEnd),
                onArchive = onArchive,
            )
        }
    }
}

/** Hairline between revealed bands — structure instead of headings. */
@Composable
private fun CardRule() {
    val eth = EthanTheme.colors
    HorizontalDivider(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
        color = eth.inkRule,
    )
}

@Composable
private fun CardCorner(modifier: Modifier = Modifier, onArchive: () -> Unit) {
    val eth = EthanTheme.colors
    var menuOpen by remember { mutableStateOf(false) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(38.dp)) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = "Card options",
                modifier = Modifier.size(19.dp),
                tint = eth.textFaint.copy(alpha = 0.45f),
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

@Composable
private fun ComponentStrip(components: List<CardComponent>) {
    val eth = EthanTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (c in components.take(CARD_COMPONENTS)) {
            val color = functionColor(c.function)
            val label = functionLabel(c.function)
            Card(
                Modifier.weight(1f),
                colors = CardDefaults.cardColors(containerColor = eth.paper1),
            ) {
                Row(
                    Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(c.char, fontFamily = NotoCjk, fontSize = 24.sp, color = color)
                    Column {
                        val loud = isSoundBearing(c.function) && c.label?.isReading == true
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            c.label?.let {
                                Text(
                                    it.text,
                                    fontFamily = PlexMono,
                                    fontSize = if (loud) 13.sp else 10.sp,
                                    lineHeight = 14.sp,
                                    color = if (loud) eth.textBody else eth.textFaint,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (label.isNotEmpty()) {
                                Text(
                                    label,
                                    fontFamily = PlexMono,
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
                                fontFamily = InstrumentSans,
                                fontSize = 12.sp,
                                lineHeight = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = eth.textBody,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BackWordRow(w: BackWord) {
    val eth = EthanTheme.colors
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(w.text, fontFamily = NotoCjk, fontSize = 17.sp, color = eth.textBody)
        w.pinyin?.let {
            Text(it, fontFamily = PlexMono, fontSize = 12.sp, color = eth.textMuted)
        }
        w.gloss?.let {
            Text(
                it,
                Modifier.weight(1f),
                fontFamily = InstrumentSans,
                fontSize = 12.sp,
                color = eth.textFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** One example sentence: the Mandarin left-aligned, its English gloss beneath in a quieter
 * tone. Full sentences are the card's tallest content, so they carry most of the burden of
 * filling the revealed side. */
@Composable
private fun BackSentenceRow(s: SentenceExample) {
    val eth = EthanTheme.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(s.zh, fontFamily = NotoCjk, fontSize = 16.sp, lineHeight = 22.sp, color = eth.textBody)
        s.en?.let {
            Text(it, fontFamily = InstrumentSans, fontSize = 12.sp, lineHeight = 16.sp, color = eth.textFaint)
        }
    }
}
