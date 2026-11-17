package com.hanzidictionary.app.photo

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.hanzidictionary.core.ImageTextRecognizer
import com.hanzidictionary.core.PhotoRef

/**
 * [ImageTextRecognizer] using ML Kit's on-device Chinese text recognizer. recognize() is
 * blocking (via Tasks.await) so it fits the synchronous core interface; PhotoScanner only
 * ever calls it from a background dispatcher.
 *
 * Two kinds of failure, kept apart: a photo that can't be opened or decoded yields "" (move
 * past it — retrying won't help), while the recognizer itself failing — the unbundled model
 * still downloading on a fresh install is the usual case — yields null, so the scanner stops
 * and comes back to this photo instead of writing the whole backlog off as text-free.
 */
class MlKitTextRecognizer(private val context: Context) : ImageTextRecognizer {
    private val client = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    override fun recognize(photo: PhotoRef): String? {
        val image = try {
            InputImage.fromFilePath(context, Uri.parse(photo.id))
        } catch (e: Exception) {
            return ""  // deleted, unreadable, or not an image
        } catch (e: OutOfMemoryError) {
            return ""  // too big to decode here; it won't get smaller on a retry
        }
        return try {
            Tasks.await(client.process(image)).text
        } catch (e: Exception) {
            null
        }
    }
}
