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
import com.hanzidictionary.core.DrillChoice
import com.hanzidictionary.core.HanziRepository
import com.hanzidictionary.core.UserRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class Round(
    val target: DrillChoice,
    val other: DrillChoice,
    val leftIsTarget: Boolean,
)

/**
 * "Which one is …?" drill over look-alike characters: two common members of one
 * phonetic series (清 vs 情 — same 青, different radical), cued by pinyin + gloss.
 * This is the visual discrimination a learner actually needs; the pack's
 * kSpoofingVariant pairs are almost all ultra-rare characters and can't feed it.
 * A wrong tap logs a confusion_event (spec §7) — raw material for smarter
 * confusable review later (spec §14).
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

    var groups by remember { mutableStateOf<List<List<DrillChoice>>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var round by remember { mutableStateOf<Round?>(null) }
    var seed by remember { mutableIntStateOf(0) }        // bump -> deal a new round
    var roundNumber by remember { mutableIntStateOf(0) } // answered rounds
    // null = unanswered; true/false = answered (correctly / not)
    var answeredCorrectly by remember { mutableStateOf<Boolean?>(null) }
    var score by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        groups = withContext(Dispatchers.IO) { repository.drillSeries() }
        loaded = true
    }
    LaunchedEffect(groups, seed) {
        if (groups.isEmpty()) return@LaunchedEffect
        val pair = groups.random().shuffled().take(2)
        round = Round(pair[0], pair[1], leftIsTarget = kotlin.random.Random.nextBoolean())
        answeredCorrectly = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Look-alikes") },
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
                groups.isEmpty() -> Text(
                    "No look-alike series in this data pack.",
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
                    Text("Which one is…", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "${r.target.pinyin} — ${r.target.gloss}",
                        fontSize = 20.sp,
                        textAlign = TextAlign.Center,
                    )

                    val answer: (Boolean) -> Unit = { pickedTarget ->
                        if (answeredCorrectly == null) {
                            answeredCorrectly = pickedTarget
                            roundNumber += 1
                            if (pickedTarget) score += 1 else scope.launch(Dispatchers.IO) {
                                userRepository.logConfusion(r.target.glyphId, r.other.glyphId)
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        ChoiceCard(
                            if (r.leftIsTarget) r.target else r.other,
                            answeredCorrectly,
                            isTargetSide = r.leftIsTarget,
                            onPick = answer,
                            modifier = Modifier.weight(1f),
                        )
                        ChoiceCard(
                            if (r.leftIsTarget) r.other else r.target,
                            answeredCorrectly,
                            isTargetSide = !r.leftIsTarget,
                            onPick = answer,
                            modifier = Modifier.weight(1f),
                        )
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

@Composable
private fun ChoiceCard(
    choice: DrillChoice,
    answered: Boolean?,
    isTargetSide: Boolean,
    onPick: (pickedTarget: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val revealed = answered != null
    val colors = when {
        revealed && isTargetSide -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        else -> CardDefaults.cardColors()
    }
    Card(
        colors = colors,
        modifier = modifier.clickable { onPick(isTargetSide) },
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(choice.char, fontSize = 64.sp)
            // After the reveal, both cards teach: this is what each one was.
            if (revealed) {
                Text(choice.pinyin, fontSize = 14.sp)
                Text(
                    choice.gloss,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
            }
        }
    }
}
