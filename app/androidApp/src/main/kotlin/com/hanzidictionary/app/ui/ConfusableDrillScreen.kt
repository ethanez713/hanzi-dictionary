package com.hanzidictionary.app.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import com.hanzidictionary.core.CharacterPage
import com.hanzidictionary.core.ConfusablePair
import com.hanzidictionary.core.HanziRepository
import com.hanzidictionary.core.UserRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class Round(
    val pair: ConfusablePair,
    val target: CharacterPage,     // the character being asked for
    val leftIsTarget: Boolean,
)

/**
 * "Which one is …?" drill over the pack's visually-confusable pairs (Unihan
 * kSpoofingVariant). A wrong tap logs a confusion_event (spec §7) — the raw material
 * for smarter confusable review later (spec §14).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfusableDrillScreen(
    repository: HanziRepository,
    userRepository: UserRepository,
    onOpenCharacter: (String) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    var pairs by remember { mutableStateOf<List<ConfusablePair>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var round by remember { mutableStateOf<Round?>(null) }
    var seed by remember { mutableIntStateOf(0) }        // bump -> deal a new round
    var roundNumber by remember { mutableIntStateOf(0) } // answered rounds
    // null = unanswered; true/false = answered (correctly / not)
    var answeredCorrectly by remember { mutableStateOf<Boolean?>(null) }
    var score by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        pairs = withContext(Dispatchers.IO) { repository.confusablePairs() }
        loaded = true
    }
    LaunchedEffect(pairs, seed) {
        if (pairs.isEmpty()) return@LaunchedEffect
        round = withContext(Dispatchers.IO) {
            val pair = pairs.random()
            val targetId = if (kotlin.random.Random.nextBoolean()) pair.glyphA else pair.glyphB
            repository.characterPage(targetId)?.let { page ->
                Round(pair, page, leftIsTarget = kotlin.random.Random.nextBoolean())
            }
        }
        answeredCorrectly = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Confusables") },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val r = round
            when {
                !loaded -> Text("Loading…")
                pairs.isEmpty() -> Text(
                    "No confusable pairs in this data pack.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                r == null -> Text("Loading…")
                else -> {
                    if (roundNumber > 0) {
                        Text(
                            "$score of $roundNumber correct",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val pinyin = r.target.readings.filter { it.system == "pinyin" }
                        .distinctBy { it.value }.joinToString("  ") { it.value }
                    Text("Which one is…", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        buildString {
                            append(pinyin)
                            r.target.definition?.let { append(" — ").append(shortSense(it)) }
                        },
                        fontSize = 20.sp,
                        textAlign = TextAlign.Center,
                    )

                    val answer: (Boolean) -> Unit = { pickedTarget ->
                        if (answeredCorrectly == null) {
                            answeredCorrectly = pickedTarget
                            roundNumber += 1
                            if (pickedTarget) score += 1 else scope.launch(Dispatchers.IO) {
                                userRepository.logConfusion(r.pair.glyphA, r.pair.glyphB)
                            }
                        }
                    }
                    val leftChar = if (r.leftIsTarget) r.target.char else otherChar(r)
                    val rightChar = if (r.leftIsTarget) otherChar(r) else r.target.char
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        ChoiceCard(leftChar, answeredCorrectly, isTargetSide = r.leftIsTarget, onPick = answer)
                        ChoiceCard(rightChar, answeredCorrectly, isTargetSide = !r.leftIsTarget, onPick = answer)
                    }

                    if (answeredCorrectly != null) {
                        Text(
                            if (answeredCorrectly == true) "Correct — ${r.target.char}"
                            else "It's the other one: ${r.target.char}",
                            fontSize = 16.sp,
                            color = if (answeredCorrectly == true) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                            modifier = Modifier.clickable { onOpenCharacter(r.target.glyphId) },
                        )
                        Button(onClick = { seed += 1 }) { Text("Next") }
                    }
                }
            }
        }
    }
}

private fun otherChar(r: Round): String =
    if (r.target.glyphId == r.pair.glyphA) r.pair.charB else r.pair.charA

private fun shortSense(definition: String): String = definition.split(";").first().trim()

@Composable
private fun ChoiceCard(
    char: String,
    answered: Boolean?,
    isTargetSide: Boolean,
    onPick: (pickedTarget: Boolean) -> Unit,
) {
    val revealed = answered != null
    val colors = when {
        revealed && isTargetSide -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        else -> CardDefaults.cardColors()
    }
    Card(
        colors = colors,
        modifier = Modifier.clickable { onPick(isTargetSide) },
    ) {
        Text(
            char,
            Modifier.padding(horizontal = 36.dp, vertical = 28.dp),
            fontSize = 64.sp,
        )
    }
}
