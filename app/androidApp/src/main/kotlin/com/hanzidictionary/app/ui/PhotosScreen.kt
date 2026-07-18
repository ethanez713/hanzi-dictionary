package com.hanzidictionary.app.ui

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.hanzidictionary.app.HanziApp
import androidx.compose.ui.text.style.TextOverflow
import com.hanzidictionary.core.HanziRepository
import com.hanzidictionary.core.SearchResult
import com.hanzidictionary.core.UserRepository
import com.hanzidictionary.core.displayGloss
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PhotosScreen(
    repository: HanziRepository,
    userRepository: UserRepository,
    onOpenCharacter: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as HanziApp

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, photoPermission) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }

    var scanning by remember { mutableStateOf(false) }
    var finds by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var rescans by remember { mutableIntStateOf(0) }

    LaunchedEffect(granted, rescans) {
        if (!granted) return@LaunchedEffect
        scanning = true
        finds = withContext(Dispatchers.IO) {
            app.createPhotoScanner().scanNewPhotos()
            val rows = userRepository.recentPhotoFinds()
            repository.glyphSummaries(rows.map { it.glyphId })
        }
        scanning = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("From your photos") },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!granted) {
                Text(
                    "Grant photo access and this screen will scan your most recent photos and " +
                        "screenshots for Chinese characters, then let you look them up.",
                )
                Button(onClick = { launcher.launch(photoPermission) }) { Text("Allow photo access") }
                return@Column
            }

            if (scanning) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text("Scanning recent photos…", Modifier.padding(top = 8.dp))
                }
            } else {
                OutlinedButton(onClick = { rescans++ }) { Text("Scan again") }
            }

            if (finds.isEmpty() && !scanning) {
                Text(
                    "No Chinese characters found in your recent photos yet. Take or save a photo " +
                        "with Chinese text, then tap Scan again.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (finds.isNotEmpty()) {
                Text(
                    if (finds.size == 1) "Found 1 character" else "Found ${finds.size} characters",
                    fontWeight = FontWeight.Bold,
                )
                // Full rows, not bare tiles: on this screen the user is reviewing what
                // was found, so each character carries its sound and meaning.
                Column {
                    for (f in finds) {
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { onOpenCharacter(f.glyphId) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(f.char, fontSize = 27.sp)
                            Column(Modifier.weight(1f)) {
                                f.primaryPinyin?.let { Text(it, fontSize = 15.sp) }
                                f.definition?.let {
                                    Text(
                                        displayGloss(it),
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
