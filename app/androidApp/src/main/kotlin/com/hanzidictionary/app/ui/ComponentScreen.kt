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
import androidx.compose.material3.LinearProgressIndicator
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
import com.hanzidictionary.core.ComponentPage
import com.hanzidictionary.core.HanziRepository
import com.hanzidictionary.core.SeriesMember
import com.hanzidictionary.core.UserRepository
import com.hanzidictionary.core.radicalFormsOf
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
            )
        },
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
                    fontSize = 56.sp,
                    lineHeight = 62.sp,
                    modifier = if (gid != null) Modifier.clickable { onOpenCharacter(gid) } else Modifier,
                )
                Column(
                    Modifier.weight(1f).padding(top = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    val pinyin = p.ownReadings.filter { it.system == "pinyin" }.joinToString("  ") { it.value }
                    if (pinyin.isNotEmpty()) Text(pinyin, fontSize = 17.sp)
                    p.definition?.let {
                        Text(it, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (gid != null) {
                        Text(
                            "Open character page →",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { onOpenCharacter(gid) }.padding(vertical = 2.dp),
                        )
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
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        for (form in related) {
                            Text(
                                form,
                                fontSize = 24.sp,
                                modifier = Modifier.clickable { onOpenComponent(form) },
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
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (p.reliability != null) ReliabilityMeter(p)

            if (p.phoneticMembers.isNotEmpty() || p.semanticMembers.isNotEmpty()) {
                // Component reunion (spec §14/§16): drill the whole series at once —
                // knowing the component makes its siblings predictable.
                TextButton(onClick = onPracticeSeries) { Text("Practice this series →") }
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
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MemberGrid(members: List<SeriesMember>, onOpenCharacter: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (m in members) {
            Card(Modifier.clickable { onOpenCharacter(m.glyphId) }) {
                Column(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(m.char, fontSize = 28.sp)
                    m.matchClass?.let {
                        Text(it, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
