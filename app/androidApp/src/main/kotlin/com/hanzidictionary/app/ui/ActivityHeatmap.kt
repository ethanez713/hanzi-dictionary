package com.hanzidictionary.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hanzidictionary.core.StreakState
import com.hanzidictionary.core.activityLevel
import com.hanzidictionary.core.activityQuartiles
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Columns. Today is always the rightmost one. */
internal const val HEATMAP_WEEKS = 26

/** Monday at the top, Sunday at the bottom. */
internal const val HEATMAP_ROWS = 7

/**
 * Days of history the grid asks the repository for. The oldest cell is 25
 * weeks plus up to six weekdays before today (181). One further day keeps a
 * daylight-saving shift from cutting that Monday off: activity is windowed in
 * exact 24h steps, and the grid is civil days.
 */
internal const val HEATMAP_LOOKBACK_DAYS = HEATMAP_WEEKS * HEATMAP_ROWS

/** Four alphas of jade500. The token itself flips between themes, so the ramp does too. */
private val JADE_ALPHA = floatArrayOf(0.40f, 0.60f, 0.80f, 1f)

/**
 * Monday starts each column and today sits in the rightmost one. A freeze is
 * an outline because a bridged day has no count of its own.
 */
@Composable
fun ActivityHeatmap(
    activity: Map<Long, Int>,
    streak: StreakState,
    today: Long,
    modifier: Modifier = Modifier,
) {
    val eth = EthanTheme.colors
    val start = gridStart(today)
    val quartiles = remember(activity, today) {
        activityQuartiles((start..today).map { activity[it] ?: 0 })
    }
    val activeDays = (start..today).count { (activity[it] ?: 0) > 0 }
    val summary = heatmapSummary(streak.current, streak.longest, activeDays)
    var selected by remember(today) { mutableStateOf(today) }
    // English, like every other label on this screen. The device locale would
    // put a translated month next to the word "words".
    val dates = remember { DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH) }
    val gap = 2.dp

    Column(modifier) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            val density = LocalDensity.current
            val gridHeight = with(density) {
                val gapPx = gap.toPx()
                val cellPx = ((maxWidth.toPx() - gapPx * (HEATMAP_WEEKS - 1)) / HEATMAP_WEEKS)
                    .coerceAtLeast(0f)
                (cellPx * HEATMAP_ROWS + gapPx * (HEATMAP_ROWS - 1)).toDp()
            }
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(gridHeight)
                    .semantics { contentDescription = summary }
                    .pointerInput(start, today) {
                        detectTapGestures { offset ->
                            val gapPx = gap.toPx()
                            val cellPx =
                                (size.width - gapPx * (HEATMAP_WEEKS - 1)) / HEATMAP_WEEKS
                            val step = cellPx + gapPx
                            if (step > 0f) {
                                val column = (offset.x / step).toInt().coerceIn(0, HEATMAP_WEEKS - 1)
                                val row = (offset.y / step).toInt().coerceIn(0, HEATMAP_ROWS - 1)
                                val day = start + column * 7L + row
                                if (day <= today) selected = day
                            }
                        }
                    },
            ) {
                val gapPx = gap.toPx()
                val cellPx = (size.width - gapPx * (HEATMAP_WEEKS - 1)) / HEATMAP_WEEKS
                if (cellPx > 0f) {
                    val step = cellPx + gapPx
                    val radius = 2.dp.toPx().coerceAtMost(cellPx / 2f)
                    val corner = CornerRadius(radius, radius)
                    val strokePx = 1.dp.toPx()
                    for (column in 0 until HEATMAP_WEEKS) {
                        for (row in 0 until HEATMAP_ROWS) {
                            val day = start + column * 7L + row
                            if (day > today) continue
                            val level = activityLevel(activity[day] ?: 0, quartiles)
                            val color = when (level) {
                                in 1..4 -> eth.jade500.copy(alpha = JADE_ALPHA[level - 1])
                                else -> eth.ink100
                            }
                            val left = column * step
                            val top = row * step
                            drawRoundRect(
                                color = color,
                                topLeft = Offset(left, top),
                                size = Size(cellPx, cellPx),
                                cornerRadius = corner,
                            )
                            if (day in streak.frozenDays) {
                                val inset = strokePx / 2f
                                drawRoundRect(
                                    color = eth.jade600,
                                    topLeft = Offset(left + inset, top + inset),
                                    size = Size(
                                        (cellPx - strokePx).coerceAtLeast(0f),
                                        (cellPx - strokePx).coerceAtLeast(0f),
                                    ),
                                    cornerRadius = CornerRadius(
                                        (radius - inset).coerceAtLeast(0f),
                                        (radius - inset).coerceAtLeast(0f),
                                    ),
                                    style = Stroke(width = strokePx),
                                )
                            }
                        }
                    }
                }
            }
        }
        Text(
            dayCaption(selected, activity[selected] ?: 0, dates),
            fontFamily = InstrumentSans,
            fontSize = 12.sp,
            color = eth.textFaint,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
        )
    }
}

/** Monday = 0. 1970-01-01 (day 0) is Thursday, so the shift is +3.
 * Kotlin's remainder keeps its sign, hence the wrap. */
private fun mondayIndex(day: Long): Int {
    val mod = (day + 3) % 7
    return (if (mod < 0) mod + 7 else mod).toInt()
}

private fun gridStart(today: Long): Long =
    today - mondayIndex(today) - (HEATMAP_WEEKS - 1) * 7L

private fun dayCaption(day: Long, count: Int, format: DateTimeFormatter): String {
    // The core day index is already a civil epoch day — (utc + offset) / 86_400_000.
    // API 26 ships java.time, so no desugaring.
    val date = LocalDate.ofEpochDay(day).format(format)
    val unit = if (count == 1) "word" else "words"
    return "$date · $count $unit"
}

private fun heatmapSummary(current: Int, longest: Int, active: Int): String =
    "Current streak $current ${dayWord(current)}, longest $longest ${dayWord(longest)}, " +
        "$active active ${dayWord(active)} in the last $HEATMAP_WEEKS weeks"

private fun dayWord(n: Int) = if (n == 1) "day" else "days"
