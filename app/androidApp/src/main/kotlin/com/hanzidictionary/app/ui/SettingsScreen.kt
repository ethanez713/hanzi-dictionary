package com.hanzidictionary.app.ui

import android.Manifest
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hanzidictionary.app.AppSettings
import com.hanzidictionary.app.PackManager
import com.hanzidictionary.app.ThemeMode
import com.hanzidictionary.app.nudge.NudgeScheduler
import com.hanzidictionary.core.HanziRepository
import com.hanzidictionary.core.NudgeMode
import com.hanzidictionary.core.SourceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * App settings (spec §15): appearance, study behaviour, and the data-source attributions that
 * previously only lived behind the search bar's ⓘ. A full-screen overlay above the tab that
 * launched it — Back or the top-bar arrow dismisses it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    repository: HanziRepository,
    onBack: () -> Unit,
) {
    val eth = EthanTheme.colors
    BackHandler(onBack = onBack)

    Scaffold(
        containerColor = eth.paper1,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = eth.paper1,
                    titleContentColor = eth.ink900,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = eth.textFaint)
                    }
                },
                title = {
                    Text(
                        "Settings",
                        fontFamily = Piazzolla,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 20.sp,
                        color = eth.ink900,
                    )
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            SettingsSectionHeader("Appearance")
            SettingsRow(
                title = "Theme",
                subtitle = "Follow the system, or lock light or dark.",
            ) {
                SegmentedChoice(
                    options = listOf(ThemeMode.AUTO to "Auto", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark"),
                    selected = settings.themeMode,
                    onSelect = { settings.updateThemeMode(it) },
                    eth = eth,
                )
            }
            HorizontalDivider(color = eth.inkRule)

            SettingsSectionHeader("Study")
            SettingsRow(
                title = "Show swipe hints",
                subtitle = "The Again/Skip/Knew labels in the practice gutters. Off widens the card.",
            ) {
                Switch(
                    checked = settings.showSwipeHints,
                    onCheckedChange = { settings.updateShowSwipeHints(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = eth.knob,
                        checkedTrackColor = eth.accent,
                        uncheckedThumbColor = eth.knob,
                        uncheckedTrackColor = eth.ink200,
                        uncheckedBorderColor = eth.borderDefault,
                    ),
                )
            }
            HorizontalDivider(color = eth.inkRule)

            RemindersSection(settings, eth)
            HorizontalDivider(color = eth.inkRule)

            SettingsSectionHeader("Data & attributions")
            Text(
                "Content pack v${PackManager.BUNDLED_PACK_VERSION}",
                fontFamily = PlexMono, fontSize = 11.sp, color = eth.textMuted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            Attributions(repository, eth)

            Spacer(Modifier.height(32.dp))
        }
    }
}

/**
 * The study-reminder notification: a flashcard you can grade from the shade. Off by default
 * (it's a notification), and switching it on is what asks for the notification permission on
 * Android 13+ — a denial leaves the switch off rather than pretending it worked.
 */
@Composable
private fun RemindersSection(settings: AppSettings, eth: EthanColors) {
    val context = LocalContext.current
    fun enable() {
        settings.updateNudgeEnabled(true)
        NudgeScheduler.sync(context, enabled = true)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) enable()
    }

    SettingsSectionHeader("Reminders")
    SettingsRow(
        title = "Flashcard reminders",
        subtitle = "A notification with a card you can mark ✗ or ✓ without opening the app.",
    ) {
        Switch(
            checked = settings.nudgeEnabled,
            onCheckedChange = { on ->
                when {
                    !on -> {
                        settings.updateNudgeEnabled(false)
                        NudgeScheduler.sync(context, enabled = false)
                    }
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED ->
                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else -> enable()
                }
            },
            colors = SwitchDefaults.colors(
                checkedThumbColor = eth.knob,
                checkedTrackColor = eth.accent,
                uncheckedThumbColor = eth.knob,
                uncheckedTrackColor = eth.ink200,
                uncheckedBorderColor = eth.borderDefault,
            ),
        )
    }
    if (!settings.nudgeEnabled) return

    SettingsRow(
        title = "When",
        subtitle = if (settings.nudgeMode == NudgeMode.ADAPTIVE) {
            "Once a day, around when you usually study. Skipped if you already did, and it backs off if ignored."
        } else {
            "Every few hours inside the window below, whenever something is due."
        },
    ) {
        SegmentedChoice(
            options = listOf(NudgeMode.ADAPTIVE to "Daily", NudgeMode.INTERVAL to "Interval"),
            selected = settings.nudgeMode,
            onSelect = { settings.updateNudgeMode(it) },
            eth = eth,
        )
    }
    if (settings.nudgeMode == NudgeMode.INTERVAL) {
        SettingsRow(title = "Every", subtitle = "Gap between reminders.") {
            SegmentedChoice(
                options = listOf(2 to "2h", 4 to "4h", 8 to "8h"),
                selected = settings.nudgeIntervalHours,
                onSelect = { settings.updateNudgeIntervalHours(it) },
                eth = eth,
            )
        }
    }
    SettingsRow(title = "Only between", subtitle = "Nothing is sent outside these hours.") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TimePill(settings.nudgeWindowStartMin, eth) { settings.updateNudgeWindowStart(it) }
            TimePill(settings.nudgeWindowEndMin, eth) { settings.updateNudgeWindowEnd(it) }
        }
    }
}

/** A time-of-day chip (minutes after midnight) that opens the platform time picker. */
@Composable
private fun TimePill(minutes: Int, eth: EthanColors, onPick: (Int) -> Unit) {
    val context = LocalContext.current
    Text(
        "%02d:%02d".format(minutes / 60, minutes % 60),
        fontFamily = PlexMono,
        fontSize = 12.sp,
        color = eth.textMuted,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(eth.paper1)
            .border(1.dp, eth.borderDefault, RoundedCornerShape(50))
            .clickable {
                TimePickerDialog(context, { _, h, m -> onPick(h * 60 + m) }, minutes / 60, minutes % 60, true).show()
            }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun Attributions(repository: HanziRepository, eth: EthanColors) {
    val sources by produceState(initialValue = emptyList<SourceInfo>()) {
        value = withContext(Dispatchers.IO) { repository.sources() }
    }
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (s in sources) {
            Column {
                Text(s.name, fontFamily = InstrumentSans, fontSize = 13.sp, color = eth.textBody)
                Text("${s.license} · ${s.version}", fontFamily = PlexMono, fontSize = 11.sp, color = eth.textFaint)
            }
        }
        Text(
            "Scheduling: FSRS-6 (Open Spaced Repetition, MIT). UI typeface: Noto Sans CJK SC " +
                "(SIL OFL 1.1). App icon strokes: makemeahanzi/hanzi-writer-data, from Arphic " +
                "KaiTi fonts (Arphic Public License). Full license texts ship in the project's " +
                "LICENSES/ directory.",
            fontFamily = InstrumentSans, fontSize = 12.sp, lineHeight = 17.sp, color = eth.textFaint,
        )
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    val eth = EthanTheme.colors
    Text(
        title.uppercase(),
        fontFamily = PlexMono,
        fontSize = 10.sp,
        letterSpacing = 0.1.sp,
        color = eth.textFaint,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp),
    )
}

/** A titled setting row with its control on the right (a switch) or below it (a wide chooser). */
@Composable
private fun SettingsRow(title: String, subtitle: String, control: @Composable () -> Unit) {
    val eth = EthanTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontFamily = InstrumentSans, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = eth.textBody)
            Text(subtitle, fontFamily = InstrumentSans, fontSize = 12.sp, lineHeight = 16.sp, color = eth.textFaint)
        }
        control()
    }
}

/** A small horizontal segmented control — one selectable pill per option. */
@Composable
private fun <T> SegmentedChoice(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    eth: EthanColors,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((value, label) in options) {
            val isOn = value == selected
            Text(
                label,
                fontFamily = InstrumentSans,
                fontSize = 12.sp,
                color = if (isOn) eth.onAccent else eth.textMuted,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (isOn) eth.accent else eth.paper1)
                    .border(1.dp, if (isOn) eth.accent else eth.borderDefault, RoundedCornerShape(50))
                    .clickable { onSelect(value) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}
