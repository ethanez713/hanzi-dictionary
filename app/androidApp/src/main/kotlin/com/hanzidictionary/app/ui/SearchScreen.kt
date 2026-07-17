package com.hanzidictionary.app.ui

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import android.graphics.Paint
import com.hanzidictionary.app.HanziApp
import com.hanzidictionary.app.R
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hanzidictionary.core.HanziRepository
import com.hanzidictionary.core.SearchResult
import com.hanzidictionary.core.SourceInfo
import com.hanzidictionary.core.WordHit
import com.hanzidictionary.core.charOfGlyphId
import com.hanzidictionary.core.extractHanzi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    repository: HanziRepository,
    query: String,
    onQueryChange: (String) -> Unit,
    onOpenCharacter: (String) -> Unit,
    onOpenWord: (String) -> Unit,
    onOpenPractice: () -> Unit,
    onOpenPhotos: () -> Unit,
) {
    var showAttributions by remember { mutableStateOf(false) }
    if (showAttributions) {
        AttributionsDialog(repository, onDismiss = { showAttributions = false })
    }

    // Paste-on-open (spec §9 capture): if the clipboard holds hanzi when the window
    // gains focus (clipboard is only readable while focused on API 29+), offer it as a
    // one-tap search. Checked once per screen life; declining just means not tapping.
    val clipboard = LocalClipboardManager.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    var clipSuggestion by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(windowFocused) {
        if (windowFocused && clipSuggestion == null && query.isBlank()) {
            val text = clipboard.getText()?.text ?: return@LaunchedEffect
            val hanzi = extractHanzi(text)
            if (hanzi.isNotEmpty()) clipSuggestion = hanzi.joinToString("").take(12)
        }
    }

    // The pack includes ultra-rare characters (unranked CJK Ext-B/C/D) that the bundled
    // Noto font has no glyph for; they'd render as tofu boxes. Drop them from results by
    // testing each against Noto so search only ever shows characters we can actually draw.
    val context = LocalContext.current
    val glyphPaint = remember {
        Paint().apply { typeface = ResourcesCompat.getFont(context, R.font.noto_sans_cjk_sc) }
    }

    // "From your photos" panel: kick the (watermark-deduped) scan off immediately so the
    // search box is usable while OCR runs; null = still scanning. Re-entering with no
    // new photos costs one MediaStore metadata query and zero OCR.
    val app = context.applicationContext as HanziApp
    var photosGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, photoPermission) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        photosGranted = it
    }
    var photoFinds by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    LaunchedEffect(photosGranted) {
        if (!photosGranted) return@LaunchedEffect
        photoFinds = null
        photoFinds = withContext(Dispatchers.IO) {
            app.createPhotoScanner().scanNewPhotos()
            val rows = app.userRepository.recentPhotoFinds(30)
            val existing = repository.existingGlyphIds(rows.map { it.glyphId })
            rows.filter { it.glyphId in existing }
                .map { it.glyphId to charOfGlyphId(it.glyphId) }
                .filter { glyphPaint.hasGlyph(it.second) }
                .take(12)
        }
    }

    val results by produceState(initialValue = emptyList<SearchResult>(), query) {
        val q = query
        value = if (q.isBlank()) emptyList()
        else withContext(Dispatchers.IO) {
            repository.search(q).filter { glyphPaint.hasGlyph(it.char) }
        }
    }
    val wordHits by produceState(initialValue = emptyList<WordHit>(), query) {
        val q = query
        value = if (q.isBlank()) emptyList()
        else withContext(Dispatchers.IO) { repository.searchWords(q) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hanzi Dictionary") },
                actions = {
                    TextButton(onClick = onOpenPhotos) { Text("Photos") }
                    TextButton(onClick = onOpenPractice) { Text("Practice") }
                    IconButton(onClick = { showAttributions = true }) {
                        Icon(Icons.Filled.Info, contentDescription = "Data sources")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                label = { Text("Character, pinyin, or English") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            )

            if (query.isBlank()) {
                clipSuggestion?.let { s ->
                    AssistChip(
                        onClick = { onQueryChange(s) },
                        label = { Text("From clipboard: $s") },
                    )
                }
                PhotoPanel(
                    granted = photosGranted,
                    finds = photoFinds,
                    onAllow = { permissionLauncher.launch(photoPermission) },
                    onOpenCharacter = onOpenCharacter,
                    onOpenPhotos = onOpenPhotos,
                )
                Text(
                    "Type a character (请), pinyin (qing), or English (feeling).",
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                    textAlign = TextAlign.Center,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (results.isEmpty() && wordHits.isEmpty()) {
                Text(
                    "No results for “$query”.",
                    modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                    textAlign = TextAlign.Center,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // Spec §9 ranking: characters (exact/pinyin/English) first, then words.
                // The two searches land at different times; if word hits render first,
                // LazyColumn anchors scroll to the words header and character rows added
                // above it end up off-screen — so re-pin to the top on every result change.
                val listState = rememberLazyListState()
                LaunchedEffect(results, wordHits) { listState.scrollToItem(0) }
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    items(results, key = { it.glyphId }) { r ->
                        SearchRow(r, onClick = { onOpenCharacter(r.glyphId) })
                        HorizontalDivider()
                    }
                    if (wordHits.isNotEmpty()) {
                        item(key = "words-header") {
                            Text(
                                "Words",
                                Modifier.padding(top = 10.dp, bottom = 2.dp),
                                fontSize = 12.sp,
                                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        items(wordHits, key = { it.id }) { w ->
                            WordSearchRow(w, onClick = { onOpenWord(w.id) })
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

/** The always-on photo strip (the user's primary capture path): characters from the
 * most recent camera-roll photos, scanned asynchronously so search stays responsive.
 * finds == null means the scan is still running. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhotoPanel(
    granted: Boolean,
    finds: List<Pair<String, String>>?,
    onAllow: () -> Unit,
    onOpenCharacter: (String) -> Unit,
    onOpenPhotos: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("From your photos")
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onOpenPhotos) { Text("All →") }
        }
        when {
            !granted -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Scan recent photos for characters.",
                    fontSize = 13.sp,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onAllow) { Text("Allow") }
            }
            finds == null -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    "Scanning recent photos…",
                    fontSize = 12.sp,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            finds.isEmpty() -> Text(
                "No characters in recent photos yet.",
                fontSize = 13.sp,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for ((glyphId, ch) in finds) {
                    Card(Modifier.clickable { onOpenCharacter(glyphId) }) {
                        Text(ch, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), fontSize = 26.sp)
                    }
                }
            }
        }
    }
}

/** The ⓘ attributions element (spec §15/§16): every data source in the pack with its
 * license, straight from the pack's own source table, plus the bundled font. */
@Composable
private fun AttributionsDialog(repository: HanziRepository, onDismiss: () -> Unit) {
    val sources by produceState(initialValue = emptyList<SourceInfo>()) {
        value = withContext(Dispatchers.IO) { repository.sources() }
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Data sources") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (s in sources) {
                    Column {
                        Text(s.name, fontSize = 14.sp)
                        Text(
                            "${s.license} · ${s.version}",
                            fontSize = 12.sp,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    "UI typeface: Noto Sans CJK SC (SIL OFL 1.1). Full license texts ship " +
                        "in the project's LICENSES/ directory.",
                    fontSize = 12.sp,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

@Composable
private fun WordSearchRow(w: WordHit, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(w.simp, fontSize = 21.sp)
        if (w.trad != w.simp) {
            Text(w.trad, fontSize = 14.sp, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.weight(1f)) {
            w.pinyin?.let { Text(it, fontSize = 14.sp) }
            w.gloss?.let {
                Text(
                    it,
                    fontSize = 13.sp,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A result row leads with what identifies the character — its sound and meaning. The
 * frequency rank was reference clutter here; it lives on the character page's Details. */
@Composable
private fun SearchRow(result: SearchResult, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(result.char, fontSize = 27.sp)
        Column(Modifier.weight(1f)) {
            result.primaryPinyin?.let { Text(it, fontSize = 15.sp) }
            result.definition?.let {
                Text(
                    it,
                    fontSize = 13.sp,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
