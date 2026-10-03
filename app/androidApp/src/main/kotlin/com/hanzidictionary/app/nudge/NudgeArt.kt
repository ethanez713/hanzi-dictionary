package com.hanzidictionary.app.nudge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.DisplayMetrics
import android.widget.RemoteViews
import androidx.annotation.ColorRes
import androidx.annotation.FontRes
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.hanzidictionary.app.R
import kotlin.math.ceil
import kotlin.math.min

/**
 * Draws the study notification's text in the app's own typefaces.
 *
 * A notification body is RemoteViews, and a RemoteViews TextView only gets system fonts — so
 * the glyph would come out in the device's CJK face instead of the Noto Sans CJK the practice
 * card uses (and tofu on the Extension characters Noto was bundled for), and the title and
 * labels would lose Piazzolla / Plex Mono / Instrument Sans. Rendering each run to a bitmap is
 * the one way to keep the design language intact in the shade.
 *
 * The bitmaps are **alpha masks** (`ALPHA_8`) tinted by their ImageView from the `nudge_*`
 * colour tokens: a quarter of the bytes of colour bitmaps — every bitmap is parcelled through
 * the binder with each notify(), which caps out around 1 MB — and one bitmap serves either
 * theme. For the same budget they're drawn at no more than [MAX_SCALE]× and tagged with that
 * density so they still lay out at their dp size on denser screens.
 */
internal class NudgeArt(private val context: Context) {
    private val metrics = context.resources.displayMetrics
    private val scale = min(metrics.density, MAX_SCALE)
    private val fontScale = context.resources.configuration.fontScale

    /** Usable body width of a notification, in dp: screen minus the shade's side margins and
     * the decorated style's own padding. An estimate — the ImageViews scale down to fit. */
    val contentWidthDp: Float = (context.resources.configuration.screenWidthDp - 64).coerceAtLeast(200).toFloat()

    /** One line of [text] at [sizeSp], ellipsized to [maxWidthDp]. [letterSpacingEm] matches
     * the theme's tracked label styles (labelSmall is 0.075em). */
    fun line(
        text: String,
        @FontRes font: Int,
        sizeSp: Float,
        maxWidthDp: Float,
        letterSpacingEm: Float = 0f,
    ): Bitmap {
        val paint = paint(font, sizeSp * fontScale * scale).apply { letterSpacing = letterSpacingEm }
        val fitted = TextUtils.ellipsize(text, paint, maxWidthDp * scale, TextUtils.TruncateAt.END).toString()
        val fm = paint.fontMetrics
        return draw(paint, fitted, height = ceil(fm.descent - fm.ascent), baseline = -fm.ascent)
    }

    /**
     * [text] as large as fits a [boxWidthDp] × [boxHeightDp] box, capped at [maxSp] — how the
     * practice card's front scales a long word down from the 132sp a single character gets.
     * Sized on the em box rather than the font's (tall) line metrics, so a CJK glyph fills the
     * box the way it fills the card's glyph zone; 一 and 龍 come out the same size, as they do
     * in the app.
     */
    fun glyph(text: String, boxWidthDp: Float, boxHeightDp: Float, maxSp: Float): Bitmap {
        val boxW = boxWidthDp * scale
        val boxH = boxHeightDp * scale
        var size = min(maxSp * scale, boxH / EM_BOX)
        val paint = paint(R.font.noto_sans_cjk_sc, size)
        val width = paint.measureText(text)
        if (width > boxW) {
            size *= boxW / width
            paint.textSize = size
        }
        return draw(paint, text, height = ceil(size * EM_BOX), baseline = size * EM_BASELINE)
    }

    private fun paint(@FontRes font: Int, sizePx: Float) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = typeface(context, font)
        textSize = sizePx
    }

    private fun draw(paint: TextPaint, text: String, height: Float, baseline: Float): Bitmap {
        val w = ceil(paint.measureText(text)).toInt().coerceAtLeast(1)
        val h = height.toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        bitmap.density = (DisplayMetrics.DENSITY_DEFAULT * scale).toInt()
        Canvas(bitmap).drawText(text, 0f, baseline, paint)
        return bitmap
    }

    companion object {
        /** Ceiling on render density; see the class note on the binder budget. */
        private const val MAX_SCALE = 2f

        /** Bitmap height per em, and where the baseline sits in it: room for an ideograph's
         * full em (≈0.88 above the baseline, ≈0.12 below) plus a little air. */
        private const val EM_BOX = 1.15f
        private const val EM_BASELINE = 0.95f

        // Loaded once per process: Noto CJK alone is a 16 MB face, and the receiver re-posts
        // on every grade.
        private val typefaces = HashMap<Int, Typeface>()

        @Synchronized
        private fun typeface(context: Context, @FontRes font: Int): Typeface =
            typefaces.getOrPut(font) { ResourcesCompat.getFont(context, font) ?: Typeface.DEFAULT }
    }
}

/** Puts [bitmap] in [viewId] tinted with the [color] token, with [description] for TalkBack. */
internal fun RemoteViews.setArt(
    context: Context,
    viewId: Int,
    bitmap: Bitmap,
    @ColorRes color: Int,
    description: String? = null,
) {
    setImageViewBitmap(viewId, bitmap)
    setInt(viewId, "setColorFilter", ContextCompat.getColor(context, color))
    if (description != null) setContentDescription(viewId, description)
}
