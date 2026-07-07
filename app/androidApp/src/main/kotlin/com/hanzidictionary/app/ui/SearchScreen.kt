package com.hanzidictionary.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.res.ResourcesCompat
import android.graphics.Paint
import com.hanzidictionary.app.R
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hanzidictionary.core.HanziRepository
import com.hanzidictionary.core.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    repository: HanziRepository,
    onOpenCharacter: (String) -> Unit,
    onOpenKnown: () -> Unit,
    onOpenPhotos: () -> Unit,
) {
    var query by remember { mutableStateOf("") }

    // The pack includes ultra-rare characters (unranked CJK Ext-B/C/D) that the bundled
    // Noto font has no glyph for; they'd render as tofu boxes. Drop them from results by
    // testing each against Noto so search only ever shows characters we can actually draw.
    val context = LocalContext.current
    val glyphPaint = remember {
        Paint().apply { typeface = ResourcesCompat.getFont(context, R.font.noto_sans_cjk_sc) }
    }

    val results by produceState(initialValue = emptyList<SearchResult>(), query) {
        val q = query
        value = if (q.isBlank()) emptyList()
        else withContext(Dispatchers.IO) {
            repository.search(q).filter { glyphPaint.hasGlyph(it.char) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hanzi Dictionary") },
                actions = {
                    TextButton(onClick = onOpenPhotos) { Text("Photos") }
                    TextButton(onClick = onOpenKnown) { Text("Known") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search character or pinyin") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            )

            if (query.isBlank()) {
                Text(
                    "Type a character (请) or pinyin (qing).",
                    modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                    textAlign = TextAlign.Center,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (results.isEmpty()) {
                Text(
                    "No results for “$query”.",
                    modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                    textAlign = TextAlign.Center,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(results, key = { it.glyphId }) { r ->
                        SearchRow(r, onClick = { onOpenCharacter(r.glyphId) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchRow(result: SearchResult, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(result.char, fontSize = 34.sp)
        Column {
            result.primaryPinyin?.let { Text(it, fontSize = 18.sp) }
            result.freqRank?.let {
                Text(
                    "freq #$it",
                    fontSize = 12.sp,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
