package com.hanzidictionary.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hanzidictionary.app.audio.MandarinTts
import com.hanzidictionary.core.CharacterPage
import com.hanzidictionary.core.HanziRepository
import com.hanzidictionary.core.UserRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What feeds the deck: characters the user has met, an HSK band to study, or one
 * phonetic/semantic series ("component reunion", spec §14/§16). */
sealed class DeckSource {
    data object Encountered : DeckSource()
    data class Hsk(val level: Int) : DeckSource()
    data class Series(val componentId: String) : DeckSource()
}

/**
 * Flashcard practice over UNKNOWN characters. Sources: encountered (lookups + photo
 * finds), an HSK level (deliberate study), or a component's series. "I knew it"
 * archives the character; reverse mode shows the meaning first and asks for the
 * character (production recall). Deliberately not an SRS — no scheduling (spec §16).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PracticeScreen(
    repository: HanziRepository,
    userRepository: UserRepository,
    hskLevels: Map<Int, List<String>>,
    tts: MandarinTts,
    initialComponentId: String?,
    onOpenCharacter: (String) -> Unit,
    onOpenConfusables: () -> Unit,
    onOpenArchive: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    var source by remember {
        mutableStateOf<DeckSource>(
            if (initialComponentId != null) DeckSource.Series(initialComponentId) else DeckSource.Encountered,
        )
    }
    var reverse by remember { mutableStateOf(false) }
    var deck by remember { mutableStateOf<List<String>>(emptyList()) }
    var index by remember { mutableIntStateOf(0) }
    var showAnswer by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var card by remember { mutableStateOf<CharacterPage?>(null) }

    LaunchedEffect(source) {
        loaded = false
        deck = withContext(Dispatchers.IO) {
            val known = userRepository.knownIds()
            val candidates = when (val s = source) {
                is DeckSource.Encountered -> userRepository.practiceQueue()
                is DeckSource.Hsk -> repository.sortByFrequency(hskLevels[s.level].orEmpty())
                is DeckSource.Series -> {
                    val page = repository.componentPage(s.componentId)
                    (page.phoneticMembers + page.semanticMembers).map { it.glyphId }.distinct()
                }
            }
            val existing = repository.existingGlyphIds(candidates)
            candidates.filter { it in existing && it !in known }
        }
        index = 0
        showAnswer = false
        loaded = true
    }
    LaunchedEffect(deck, index) {
        card = deck.getOrNull(index)?.let { gid ->
            withContext(Dispatchers.IO) { repository.characterPage(gid) }
        }
    }

    fun advance(markKnown: Boolean) {
        val gid = deck.getOrNull(index) ?: return
        if (markKnown) {
            scope.launch(Dispatchers.IO) { userRepository.setKnown(gid, true) }
            deck = deck.filterIndexed { i, _ -> i != index }
            if (index >= deck.size) index = 0
        } else {
            index = if (deck.isNotEmpty()) (index + 1) % deck.size else 0
        }
        showAnswer = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Practice") },
                navigationIcon = { BackButton(onBack) },
                actions = { TextButton(onClick = onOpenArchive) { Text("Archive") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            DeckPicker(source, initialComponentId, hskLevels.keys.sorted(), onPick = { source = it })
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(
                    selected = reverse,
                    onClick = { reverse = !reverse; showAnswer = false },
                    label = { Text("Reverse: meaning → character") },
                )
                OutlinedButton(onClick = onOpenConfusables) { Text("Confusables") }
            }

            when {
                !loaded -> Text("Loading…")
                deck.isEmpty() -> Text(
                    when (source) {
                        is DeckSource.Encountered ->
                            "Nothing to practice. Characters you look up or find in photos " +
                                "land here until you archive them as known."
                        is DeckSource.Hsk -> "Every character in this HSK band is archived. 好棒!"
                        is DeckSource.Series -> "Every character in this series is archived."
                    },
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 32.dp),
                )
                else -> {
                    Text(
                        "${index + 1} of ${deck.size}",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    PracticeCard(
                        card = card,
                        reverse = reverse,
                        showAnswer = showAnswer,
                        tts = tts,
                        onReveal = { showAnswer = true },
                        onOpenCharacter = onOpenCharacter,
                    )
                    if (showAnswer) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            OutlinedButton(onClick = { advance(markKnown = false) }) {
                                Text("Still learning")
                            }
                            Button(onClick = { advance(markKnown = true) }) {
                                Text("I knew it ✓")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeckPicker(
    source: DeckSource,
    initialComponentId: String?,
    hskLevelsSorted: List<Int>,
    onPick: (DeckSource) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (initialComponentId != null) {
            FilterChip(
                selected = source is DeckSource.Series,
                onClick = { onPick(DeckSource.Series(initialComponentId)) },
                label = { Text("$initialComponentId series") },
            )
        }
        FilterChip(
            selected = source is DeckSource.Encountered,
            onClick = { onPick(DeckSource.Encountered) },
            label = { Text("Encountered") },
        )
        for (level in hskLevelsSorted) {
            FilterChip(
                selected = (source as? DeckSource.Hsk)?.level == level,
                onClick = { onPick(DeckSource.Hsk(level)) },
                label = { Text(if (level == 7) "HSK 7-9" else "HSK $level") },
            )
        }
    }
}

@Composable
private fun PracticeCard(
    card: CharacterPage?,
    reverse: Boolean,
    showAnswer: Boolean,
    tts: MandarinTts,
    onReveal: () -> Unit,
    onOpenCharacter: (String) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (card == null) {
                Text("…", fontSize = 96.sp)
                return@Card
            }
            val pinyin = card.readings.filter { it.system == "pinyin" }
                .distinctBy { it.value }.joinToString("  ") { it.value }
            val gloss = card.definition ?: "No English gloss for this character."

            // Front: character (normal) or meaning (reverse, production recall).
            if (!reverse) {
                Text(card.char, fontSize = 96.sp, lineHeight = 104.sp)
            } else {
                Text(gloss, fontSize = 22.sp, textAlign = TextAlign.Center, lineHeight = 30.sp)
            }

            if (!showAnswer) {
                Button(onClick = onReveal) { Text("Show answer") }
            } else {
                if (reverse) Text(card.char, fontSize = 96.sp, lineHeight = 104.sp)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(pinyin, fontSize = 24.sp)
                    if (tts.available) {
                        TextButton(onClick = { tts.speak(card.char) }) { Text("🔊") }
                    }
                }
                if (!reverse) Text(gloss, fontSize = 17.sp, textAlign = TextAlign.Center)
                card.words.firstOrNull()?.let { w ->
                    Text(
                        "${w.simp} ${w.pinyin ?: ""} — ${w.gloss ?: ""}",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                TextButton(onClick = { onOpenCharacter(card.glyphId) }) {
                    Text("Full character page →")
                }
            }
        }
    }
}
