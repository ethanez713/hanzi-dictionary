package com.hanzidictionary.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hanzidictionary.app.audio.MandarinTts
import com.hanzidictionary.core.HanziRepository
import com.hanzidictionary.core.StudyItem
import com.hanzidictionary.core.WordPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Word page (spec §15 IA): header with both forms, pinyin + device-TTS audio, defs,
 * HSK chip, and a per-character breakdown strip linking into character pages. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WordScreen(
    repository: HanziRepository,
    tts: MandarinTts,
    wordId: String,
    onOpenCharacter: (String) -> Unit,
    onHome: () -> Unit,
    onBack: () -> Unit,
) {
    var loaded by remember(wordId) { mutableStateOf(false) }
    val page by produceState<WordPage?>(initialValue = null, wordId) {
        value = withContext(Dispatchers.IO) { repository.wordPage(wordId) }
        loaded = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(page?.simp ?: "…") },
                navigationIcon = { BackButton(onBack) },
                actions = { HomeButton(onHome) },
            )
        },
    ) { padding ->
        val p = page
        if (p == null) {
            Text(
                if (loaded) "This word isn't in the data pack." else "Loading…",
                Modifier.padding(padding).padding(16.dp),
            )
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(p.simp, fontSize = 44.sp, lineHeight = 52.sp)
                if (p.trad != p.simp) {
                    Text(p.trad, fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    p.pinyin?.let { Text(it, fontSize = 20.sp) }
                    if (tts.available) {
                        // Whole-word TTS: the engine applies tone sandhi itself, which is
                        // exactly why the spec kept word audio on the device engine (§9).
                        Text("🔊", fontSize = 18.sp, modifier = Modifier.clickable { tts.speak(p.simp) })
                    }
                    p.hsk?.let { Chip("HSK $it") }
                    if (isCommon(p.freqRank)) Chip("Common")
                }
            }

            // Words are studiable in their own right (spec §17), so the word page carries
            // the same primary action as the character page.
            StudyActionRow(item = StudyItem.word(wordId), label = p.simp)

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for ((i, sense) in p.senses.withIndex()) {
                    Text("${i + 1}. $sense", fontSize = 16.sp, lineHeight = 22.sp)
                }
            }

            // Seeing the word in real sentences is the point of the page (spec §9). Each
            // Mandarin line can be spoken; the English gloss sits under it, muted.
            if (p.sentences.isNotEmpty()) {
                SectionTitle("Sentences")
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (s in p.sentences) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    highlightWord(s.zh, p.simp),
                                    fontSize = 17.sp,
                                    lineHeight = 24.sp,
                                    modifier = Modifier.weight(1f),
                                )
                                if (tts.available) {
                                    Text("🔊", fontSize = 15.sp, modifier = Modifier.clickable { tts.speak(s.zh) })
                                }
                            }
                            s.en?.let {
                                Text(
                                    it,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    lineHeight = 18.sp,
                                )
                            }
                        }
                    }
                    Text(
                        "Sentences from Tatoeba (CC BY 2.0)",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (p.chars.isNotEmpty()) {
                SectionTitle("Characters")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    for (c in p.chars) {
                        Card(Modifier.clickable { onOpenCharacter(c.glyphId) }) {
                            Row(
                                Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(c.char, fontSize = 34.sp)
                                c.definition?.let {
                                    Text(
                                        it.split(";").first().trim(),
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Characters a learner confuses with this word's characters — series siblings and
            // visual look-alikes — as full rows (same element as the photo feed), each opening
            // its own character page.
            if (p.lookAlikes.isNotEmpty()) {
                SectionTitle("Look-alikes")
                Column {
                    for (la in p.lookAlikes) {
                        ItemRow(
                            text = la.char,
                            pinyin = la.pinyin,
                            gloss = la.gloss,
                            glossLines = 1,
                            onClick = { onOpenCharacter(la.glyphId) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Chip(text: String) {
    Card {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 12.sp)
    }
}

/** The Mandarin sentence with every occurrence of [word] in bold, so it's obvious at a
 * glance which word the example is illustrating. */
private fun highlightWord(sentence: String, word: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (true) {
        val at = sentence.indexOf(word, i)
        if (at < 0) {
            append(sentence.substring(i))
            break
        }
        append(sentence.substring(i, at))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(word) }
        i = at + word.length
    }
}
