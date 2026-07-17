package com.hanzidictionary.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.font.FontWeight
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

/**
 * Flashcard practice over characters the user has ENCOUNTERED — looked up, or found in
 * a photo scan — but not yet archived as known. This is the flip side of the known
 * model: encounters feed the deck, "I knew it" drains a card into the known archive
 * (which in turn powers known-first series ordering). Deliberately not an SRS — no
 * scheduling, just a self-check pass over the current deck (spec §16).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PracticeScreen(
    repository: HanziRepository,
    userRepository: UserRepository,
    tts: MandarinTts,
    onOpenCharacter: (String) -> Unit,
    onOpenArchive: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    var deck by remember { mutableStateOf<List<String>>(emptyList()) }
    var index by remember { mutableIntStateOf(0) }
    var showAnswer by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var card by remember { mutableStateOf<CharacterPage?>(null) }

    LaunchedEffect(Unit) {
        deck = withContext(Dispatchers.IO) {
            // Filter to pack coverage: photo finds can record characters outside T1/T2.
            val queue = userRepository.practiceQueue()
            val existing = repository.existingGlyphIds(queue)
            queue.filter { it in existing }
        }
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
                navigationIcon = { TextButton(onClick = onBack) { Text("←") } },
                actions = { TextButton(onClick = onOpenArchive) { Text("Archive") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when {
                !loaded -> Text("Loading…")
                deck.isEmpty() -> Text(
                    "Nothing to practice. Characters you look up or find in photos land " +
                        "here until you archive them as known.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 48.dp),
                )
                else -> {
                    Text(
                        "${index + 1} of ${deck.size}",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    PracticeCard(
                        card = card,
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
                        Text(
                            "\"I knew it\" archives the character (and floats it first in series).",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PracticeCard(
    card: CharacterPage?,
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
            Text(card.char, fontSize = 96.sp, lineHeight = 104.sp)
            if (!showAnswer) {
                Button(onClick = onReveal) { Text("Show answer") }
            } else {
                val pinyin = card.readings.filter { it.system == "pinyin" }
                    .distinctBy { it.value }.joinToString("  ") { it.value }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(pinyin, fontSize = 24.sp)
                    if (tts.available) {
                        TextButton(onClick = { tts.speak(card.char) }) { Text("🔊") }
                    }
                }
                Text(
                    card.definition ?: "No English gloss for this character.",
                    fontSize = 17.sp,
                    textAlign = TextAlign.Center,
                )
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
                Spacer(Modifier.height(0.dp))
            }
        }
    }
}
