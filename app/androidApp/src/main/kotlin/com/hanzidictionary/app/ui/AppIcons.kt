package com.hanzidictionary.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Icons the app needs that `material-icons-core` doesn't ship.
 *
 * The alternative is `material-icons-extended`, which pulls in several thousand vectors to
 * get one of them — so the handful we actually need are declared here instead. Path data is
 * Material Symbols' own, at the standard 24dp viewport.
 *
 * These exist because the TTS control used to be the literal emoji "🔊", which renders from
 * the system *emoji* font: full colour, its own optical weight, immune to `tint`. Next to a
 * row of flat monochrome Material icons it read as a sticker dropped onto the page.
 */
object AppIcons {
    val Speaker: ImageVector by lazy { materialVector("speaker", VOLUME_UP_PATH) }
}

private const val VOLUME_UP_PATH =
    "M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02z" +
        "M14 3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 7-4.49 7-8.77" +
        "s-2.99-7.86-7-8.77z"

private fun materialVector(name: String, pathData: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(
        pathData = PathParser().parsePathString(pathData).toNodes(),
        // Black, never a scheme colour: an Icon's `tint` multiplies this, so a coloured
        // fill here would tint twice and no call site could get the colour it asked for.
        fill = SolidColor(Color.Black),
    ).build()
