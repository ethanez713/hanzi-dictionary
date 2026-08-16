package com.hanzidictionary.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hanzidictionary.app.HanziApp
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
 *
 * **My words** (the default whenever you have any) narrows it to characters you actually
 * study: the target is always one of yours — saved in a list or archived — and the character
 * it's set against is either another of yours or one common enough that you will meet it.
 * Drilling two characters you've never seen only teaches you which of two strangers is which;
 * the confusion worth practising is the one waiting in your own deck.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfusableDrillScreen(
    repository: HanziRepository,
    userRepository: UserRepository,
    onOpenCharacter: (String) -> Unit,
    onBack: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as HanziApp
    val scope = rememberCoroutineScope()

    var mineOnly by rememberSaveable { mutableStateOf(true) }
    // Groups from the user's own items, and the pooled fallback. Both are loaded once and
    // kept, so flipping the toggle mid-session is instant and doesn't re-query.
    var mineGroups by remember { mutableStateOf<List<List<DrillChoice>>?>(null) }
    var poolGroups by remember { mutableStateOf<List<List<DrillChoice>>?>(null) }
    var round by remember { mutableStateOf<Round?>(null) }
    var seed by remember { mutableIntStateOf(0) }        // bump -> deal a new round
    var roundNumber by remember { mutableIntStateOf(0) } // answered rounds
    // null = unanswered; true/false = answered (correctly / not)
    var answeredCorrectly by remember { mutableStateOf<Boolean?>(null) }
    var score by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        mineGroups = withContext(Dispatchers.IO) {
            // Both halves of "what I study": the lists I file into, and the archive of what
            // I've already claimed to know — an archived character is exactly the kind you
            // start mixing up again.
            val mine = app.studyRepository.allListItems().map { it.itemId } + userRepository.knownIds()
            repository.drillSeriesFor(mine.toSet())
        }
    }
    // The pooled drill is built only when it's actually needed — the toggle is off, or there
    // was nothing of the user's to anchor rounds on — and then only once.
    val needsPool = !mineOnly || mineGroups?.isEmpty() == true
    LaunchedEffect(needsPool) {
        if (needsPool && poolGroups == null) {
            poolGroups = withContext(Dispatchers.IO) { repository.drillSeries() }
        }
    }

    // What the toggle actually resolves to: "my words" falls back to the pool rather than
    // showing an empty screen, and says so below.
    val anchored = mineOnly && !mineGroups.isNullOrEmpty()
    val groups = if (anchored) mineGroups else poolGroups
    val loaded = groups != null

    LaunchedEffect(groups, seed) {
        val g = groups
        if (g.isNullOrEmpty()) return@LaunchedEffect
        val group = g.random()
        // Anchored groups are target-first by contract: the character being asked about has
        // to be one of the user's, so only the distractor is drawn at random.
        val target = if (anchored) group.first() else group.random()
        val other = group.filter { it.glyphId != target.glyphId }.randomOrNull() ?: return@LaunchedEffect
        round = Round(target, other, leftIsTarget = kotlin.random.Random.nextBoolean())
        answeredCorrectly = null
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.exclude(WindowInsets.navigationBars),
        topBar = {
            TopAppBar(
                title = { Text("Look-alikes") },
                navigationIcon = { BackButton(onBack) },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The scope switch sits above the question, because it changes what the question
            // is about. Off is still a real mode: sometimes you want the pack's whole pool.
            FilterChip(
                selected = mineOnly,
                onClick = {
                    mineOnly = !mineOnly
                    // A different set of characters is a different exercise; carrying the
                    // running score across would make it a tally of two of them.
                    roundNumber = 0
                    score = 0
                },
                label = { Text("My words") },
            )
            if (mineOnly && mineGroups?.isEmpty() == true) {
                Text(
                    "Nothing you've saved or archived has a look-alike yet — drilling the " +
                        "common pool instead. Swipe right on characters to build it up.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            val r = round
            when {
                !loaded -> Text("Loading…")
                groups.isNullOrEmpty() -> Text(
                    "No look-alike series in this data pack.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                r == null -> Text("Loading…")
                else -> {
                    if (roundNumber > 0) {
                        Text(
                            "$score of $roundNumber correct",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "Which one is…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "${r.target.pinyin} — ${r.target.gloss}",
                        style = MaterialTheme.typography.titleLarge,
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
                        TextButton(onClick = { onOpenCharacter(r.target.glyphId) }) {
                            Text(
                                if (answeredCorrectly == true) "Correct — ${r.target.char}"
                                else "It's the other one: ${r.target.char}",
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (answeredCorrectly == true) LocalSemanticColors.current.affirm
                                else LocalSemanticColors.current.negative,
                            )
                        }
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
    val semantic = LocalSemanticColors.current
    // Wrong is always the non-target card: with only two choices, picking the one that
    // isn't the target is what "wrong" means, so no separate "which one did they tap"
    // state is needed to mark the card they actually chose.
    val colors = when {
        revealed && isTargetSide -> CardDefaults.cardColors(containerColor = semantic.affirm.copy(alpha = 0.18f))
        revealed && !isTargetSide && answered == false ->
            CardDefaults.cardColors(containerColor = semantic.negative.copy(alpha = 0.18f))
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
            Text(choice.char, style = LocalGlyphType.current.large)
            // After the reveal, both cards teach: this is what each one was.
            if (revealed) {
                Text(choice.pinyin, style = MaterialTheme.typography.bodyMedium)
                Text(
                    choice.gloss,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
            }
        }
    }
}
