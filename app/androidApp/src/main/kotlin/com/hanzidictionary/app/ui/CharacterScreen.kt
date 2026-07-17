package com.hanzidictionary.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.hanzidictionary.app.audio.MandarinTts
import androidx.compose.ui.text.style.TextOverflow
import com.hanzidictionary.core.CharacterPage
import com.hanzidictionary.core.HanziRepository
import com.hanzidictionary.core.ReadingView
import com.hanzidictionary.core.UserRepository
import com.hanzidictionary.core.WordView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CharacterScreen(
    repository: HanziRepository,
    userRepository: UserRepository,
    tts: MandarinTts,
    glyphId: String,
    onOpenComponent: (String) -> Unit,
    onOpenCharacter: (String) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    // null page is ambiguous (still loading vs. not in the pack), so track load completion
    // separately to give the not-found case its own state (spec §15 empty states).
    var loaded by remember(glyphId) { mutableStateOf(false) }
    val page by produceState<CharacterPage?>(initialValue = null, glyphId) {
        value = withContext(Dispatchers.IO) { repository.characterPage(glyphId) }
        loaded = true
    }
    var known by remember { mutableStateOf(false) }
    LaunchedEffect(glyphId) {
        known = withContext(Dispatchers.IO) {
            userRepository.logLookup(glyphId)
            userRepository.isKnown(glyphId)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(page?.char ?: "…") },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { padding ->
        val p = page
        if (p == null) {
            Text(
                if (loaded) "This character isn't in the data pack." else "Loading…",
                Modifier.padding(padding).padding(16.dp),
            )
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Header(p, tts)
            // One compact row for both controls: the archive toggle ("I know this"
            // removes the character from the practice deck) and the reference-data
            // drawer. Stacked, these two burned a whole screen row each.
            var showDetails by remember { mutableStateOf(false) }
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = known,
                    onClick = {
                        val newValue = !known
                        known = newValue
                        scope.launch(Dispatchers.IO) { userRepository.setKnown(glyphId, newValue) }
                    },
                    label = { Text(if (known) "✓ Known" else "I know this") },
                )
                TextButton(onClick = { showDetails = !showDetails }) {
                    Text(if (showDetails) "Hide details ▴" else "Details ▾")
                }
            }
            if (showDetails) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    p.freqRank?.let { Chip("freq #$it") }
                    p.hsk?.let { Chip("HSK $it") }
                    Chip(scriptLabel(p.scripts))
                    p.strokeCount?.let { Chip("$it strokes") }
                    // Raw IDS notation: its ⿰-style operators draw as dashed layout
                    // boxes and it restates the component characters, so it belongs
                    // with the reference data rather than above the breakdown.
                    p.ids?.let { Chip(it) }
                }
            }
            if (p.words.isNotEmpty()) Words(p.words)
            BreakdownStrip(p, tts, onOpenComponent)
            SoundLine(p)
            Formation(p)
            if (p.variants.isNotEmpty()) ScriptMapping(p, onOpenCharacter)
            if (p.confusables.isNotEmpty()) Confusables(p, onOpenCharacter)
            Provenance(p)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Header(p: CharacterPage, tts: MandarinTts) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Character on the left, meaning and sound beside it: stacking these vertically
        // left the whole right half of the page empty.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(p.char, fontSize = 88.sp, lineHeight = 96.sp)
            Column(
                Modifier.weight(1f).padding(top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Every reading (多音字: all of them, spec §15) as its own chip. Only the
                // primary (highest-frequency, sorted first) reading is playable: the device
                // TTS reads a bare character with its most common pronunciation, so a
                // speaker on a secondary reading would play the wrong sound (spec §16).
                val pinyinReadings = p.readings.filter { it.system == "pinyin" }.distinctBy { it.value }
                if (pinyinReadings.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pinyinReadings.forEachIndexed { i, r ->
                            ReadingChip(r, playable = i == 0 && tts.available, onPlay = { tts.speak(p.char) })
                        }
                    }
                }
                Definition(p.definition)
            }
        }
    }
}

/** Common words containing this character (spec §9 IA item 6 "In your words"), the
 * "phrases that use the word" a learner actually meets it in. */
@Composable
private fun Words(words: List<WordView>) {
    SectionTitle("In words")
    for (w in words) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(w.simp, fontSize = 22.sp)
            // The traditional form matters but shouldn't shout at the same size as the
            // headword — smaller and muted keeps the row scannable.
            if (w.trad != w.simp) {
                Text(w.trad, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            w.pinyin?.let { Text(it, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            w.gloss?.let {
                Text(
                    it,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ReadingChip(reading: ReadingView, playable: Boolean, onPlay: () -> Unit) {
    Card(modifier = if (playable) Modifier.clickable(onClick = onPlay) else Modifier) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(reading.value, fontSize = 20.sp)
            if (isNeutralTone(reading.value)) {
                Text(
                    "neutral",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (playable) Text("🔊", fontSize = 16.sp)
        }
    }
}

/** The plain-English gloss (Unihan kDefinition) — the first thing a learner wants, so it
 * sits right beside the character itself. Unihan carries no gloss for many rare
 * characters; say so explicitly rather than leaving a blank that reads as a bug. */
@Composable
private fun Definition(text: String?) {
    if (text != null) {
        Text(text, fontSize = 18.sp, lineHeight = 24.sp)
    } else {
        Text(
            "No English gloss for this character.",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BreakdownStrip(p: CharacterPage, tts: MandarinTts, onOpenComponent: (String) -> Unit) {
    if (p.breakdown.isEmpty()) return
    SectionTitle("Components")
    // One card per component: the glyph, its role, and what it actually contributes —
    // a meaning-component shows its own gloss, a sound-component its reading (tappable
    // to hear it). Cards stack full-width so the contribution text has room.
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (c in p.breakdown) {
            val color = functionColor(c.function)
            val showsSound = c.function == "phonetic" || c.function == "both"
            val showsMeaning = c.function == "semantic" || c.function == "both"
            Card(
                modifier = Modifier.fillMaxWidth().then(
                    if (c.navigable) Modifier.clickable { onOpenComponent(c.componentId) } else Modifier,
                ),
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(c.displayChar ?: "◎", fontSize = 44.sp, color = color)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        val label = functionLabel(c.function)
                        if (label.isNotEmpty()) Text(label, fontSize = 12.sp, color = color)
                        if (showsSound && c.pinyin != null) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(c.pinyin!!, fontSize = 17.sp)
                                if (tts.available && c.displayChar != null) {
                                    Text(
                                        "🔊",
                                        fontSize = 14.sp,
                                        modifier = Modifier.clickable { tts.speak(c.displayChar!!) },
                                    )
                                }
                            }
                        }
                        if (showsMeaning && c.definition != null) {
                            Text(
                                shortGloss(c.definition!!),
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        // For unclassified parts, the component's own gloss is still the
                        // most useful hint we have — shown muted, not asserted as a role.
                        if (!showsSound && !showsMeaning && c.definition != null) {
                            Text(
                                shortGloss(c.definition!!),
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
}

/** First sense only — component cards want a hint ("words, speech"), not the full gloss. */
private fun shortGloss(definition: String): String =
    definition.split(";").first().trim()

@Composable
private fun SoundLine(p: CharacterPage) {
    val phon = p.breakdown.firstOrNull { it.function == "phonetic" || it.function == "both" } ?: return
    val sentence = driftSentence(phon.matchClass) ?: return
    SectionTitle("Sound")
    Text(sentence)
    phon.confidence?.let {
        Text(
            "sound-match confidence ${"%.0f".format(it * 100)}%",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Formation(p: CharacterPage) {
    val relevant = p.claims.filter { it.predicate == "component_function" || it.predicate == "etymology_text" }
    if (relevant.isEmpty()) return
    SectionTitle("Formation")
    for (claim in relevant) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(prettyClaim(claim.predicate, claim.value))
                Text(
                    "source: ${claim.source}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ScriptMapping(p: CharacterPage, onOpenCharacter: (String) -> Unit) {
    SectionTitle("Simplified / Traditional")
    // A merger = one simplified form standing in for several distinct traditional
    // characters; warn because the traditional side isn't recoverable from the simplified
    // one alone (spec §9 IA item 5 "merger warnings").
    val mergerTargets = p.variants.groupBy { it.toGlyph }.filterValues { it.size > 1 }.keys
    for (v in p.variants) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(v.fromChar, fontSize = 28.sp, modifier = Modifier.clickable { onOpenCharacter(v.fromGlyph) })
                    Text("→")
                    Text(v.toChar, fontSize = 28.sp, modifier = Modifier.clickable { onOpenCharacter(v.toGlyph) })
                    v.mappingType?.let {
                        Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (v.mappingType == "merger" || v.toGlyph in mergerTargets) {
                    Text(
                        "⚠ Merger: ${v.toChar} stands in for multiple traditional characters — the traditional form can't be recovered from it alone.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Confusables(p: CharacterPage, onOpenCharacter: (String) -> Unit) {
    SectionTitle("Easily confused with")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (c in p.confusables) {
            Card(Modifier.clickable { onOpenCharacter(c.glyphId) }) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(p.char, fontSize = 28.sp)
                    Text("vs", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(c.char, fontSize = 28.sp)
                }
            }
        }
    }
}

@Composable
private fun Provenance(p: CharacterPage) {
    SectionTitle("Sources")
    val claimSources = p.claims.map { it.source }.toSortedSet()
    val text = "Readings/variants from Unihan; decomposition from cjkvi-ids/makemeahanzi" +
        if (claimSources.isNotEmpty()) "; claims from ${claimSources.joinToString(", ")}" else ""
    Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Chip(text: String) {
    Card {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 12.sp)
    }
}

private fun scriptLabel(scripts: String): String = when (scripts) {
    "simp" -> "simplified"
    "trad" -> "traditional"
    else -> "both scripts"
}

private fun prettyClaim(predicate: String, value: String): String = try {
    when (predicate) {
        "component_function" -> {
            val o = JSONObject(value)
            val type = o.optString("formation_type", "").ifEmpty { "unknown formation" }
            val hint = o.optString("hint", "")
            val sem = o.optString("semantic_component", "")
            val phon = o.optString("phonetic_component", "")
            buildString {
                append(type)
                if (sem.isNotEmpty()) append("  ·  meaning: $sem")
                if (phon.isNotEmpty()) append("  ·  sound: $phon")
                if (hint.isNotEmpty()) append("\n$hint")
            }
        }
        "etymology_text" -> {
            // value is a JSON-encoded string
            if (value.startsWith("\"")) JSONObject("{\"t\":$value}").getString("t") else value
        }
        else -> value
    }
} catch (e: Exception) {
    value
}
