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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hanzidictionary.core.ComponentPage
import com.hanzidictionary.core.HanziRepository
import com.hanzidictionary.core.SeriesMember
import com.hanzidictionary.core.UserRepository
import com.hanzidictionary.core.radicalFormsOf
import com.hanzidictionary.core.radicalNameOf
import com.hanzidictionary.core.radicalParentOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComponentScreen(
    repository: HanziRepository,
    userRepository: UserRepository,
    componentId: String,
    onOpenCharacter: (String) -> Unit,
    onOpenComponent: (String) -> Unit,
    onPracticeSeries: () -> Unit,
    onHome: () -> Unit,
    onBack: () -> Unit,
) {
    val page by produceState<ComponentPage?>(initialValue = null, componentId) {
        value = withContext(Dispatchers.IO) {
            repository.componentPage(componentId, userRepository.knownIds())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(page?.char ?: "component") },
                navigationIcon = { BackButton(onBack) },
                actions = { HomeButton(onHome) },
            )
        },
        floatingActionButton = { ReturnToCardFab() },
    ) { padding ->
        val p = page
        if (p == null) {
            Text("Loading…", Modifier.padding(padding).padding(16.dp))
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Same left-aligned header as the character page — the old centered hero
            // glyph pushed the series grid (this page's payload) below the fold.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // When the component is itself a dictionary character, its glyph links
                // through to the full character page (definition, words, breakdown).
                val gid = p.glyphId
                Text(
                    p.char ?: p.componentId,
                    style = LocalGlyphType.current.large,
                    modifier = if (gid != null) {
                        Modifier.minimumInteractiveComponentSize().clickable { onOpenCharacter(gid) }
                    } else Modifier,
                )
                Column(
                    Modifier.weight(1f).padding(top = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    // Same rule as the breakdown cards: a combining form gets the name it's
                    // known by, and only a standalone character gets a reading — otherwise
                    // this page would call 氵 "shui" while every card calls it 三点水.
                    val name = p.char?.let { radicalNameOf(it) }
                    val pinyin = p.ownReadings.filter { it.system == "pinyin" }.joinToString("  ") { it.value }
                    when {
                        name != null -> Text(name, style = MaterialTheme.typography.titleMedium)
                        pinyin.isNotEmpty() -> Text(pinyin, style = MaterialTheme.typography.titleMedium)
                    }
                    p.definition?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (gid != null) {
                        TextButton(onClick = { onOpenCharacter(gid) }) {
                            Text("Open character page", style = MaterialTheme.typography.bodyMedium)
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                        }
                    }
                }
            }

            // Positional variants (spec §9): 水's combining forms 氵/氺, or the parent
            // of a combining form — each links to its own component page.
            p.char?.let { ch ->
                val related = radicalFormsOf(ch) + listOfNotNull(radicalParentOf(ch))
                if (related.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "Related forms:",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        for (form in related) {
                            Text(
                                form,
                                style = LocalGlyphType.current.small,
                                modifier = Modifier.minimumInteractiveComponentSize()
                                    .clickable { onOpenComponent(form) },
                            )
                        }
                    }
                }
            }

            // Function distribution (spec §9): how this component is used across the pack.
            if (p.phoneticMembers.isNotEmpty() || p.semanticMembers.isNotEmpty()) {
                Text(
                    buildString {
                        append("Used as ")
                        val parts = mutableListOf<String>()
                        if (p.phoneticMembers.isNotEmpty()) parts += "sound in ${p.phoneticMembers.size}"
                        if (p.semanticMembers.isNotEmpty()) parts += "meaning in ${p.semanticMembers.size}"
                        append(parts.joinToString(" · "))
                        append(" characters")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (p.reliability != null) ReliabilityMeter(p)

            if (p.phoneticMembers.isNotEmpty() || p.semanticMembers.isNotEmpty()) {
                // Component reunion (spec §14/§16): drill the whole series at once —
                // knowing the component makes its siblings predictable.
                TextButton(onClick = onPracticeSeries) {
                    Text("Practice this series")
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                }
            }

            if (p.phoneticMembers.isNotEmpty()) {
                SectionTitle("Phonetic series (${p.phoneticMembers.size})")
                MemberGrid(p.phoneticMembers, onOpenCharacter)
            }
            if (p.semanticMembers.isNotEmpty()) {
                SectionTitle("Used for meaning (${p.semanticMembers.size})")
                MemberGrid(p.semanticMembers, onOpenCharacter)
            }
            if (p.phoneticMembers.isEmpty() && p.semanticMembers.isEmpty()) {
                Text(
                    "No indexed characters use this component.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ReliabilityMeter(p: ComponentPage) {
    SectionTitle("Phonetic reliability")
    LinearProgressIndicator(
        progress = { (p.reliability ?: 0.0).toFloat().coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth(),
    )
    val stratum = when (p.bestStratum) {
        "pinyin" -> "still holds in modern Mandarin"
        "mc" -> "holds in Middle Chinese"
        "oc" -> "only holds in Old Chinese"
        else -> null
    }
    Text(
        buildString {
            append("${"%.0f".format((p.reliability ?: 0.0) * 100)}% across ${p.memberCount} characters")
            if (stratum != null) append(" · $stratum")
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MemberGrid(members: List<SeriesMember>, onOpenCharacter: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (m in members) {
            // A step up the tonal scale, not down: these cards sit on the same background
            // as the rest of the page, and dropping to `surface` reads as a hole punched
            // through it in dark mode.
            Card(
                modifier = Modifier.clickable { onOpenCharacter(m.glyphId) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Column(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(m.char, style = LocalGlyphType.current.medium)
                    m.matchClass?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
