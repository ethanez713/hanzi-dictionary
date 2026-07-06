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
 * ever calls it from a background dispatcher. A photo that can't be read yields "" rather
 * than throwing, so one bad image doesn't abort a scan.
 */
class MlKitTextRecognizer(private val context: Context) : ImageTextRecognizer {
    private val client = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    override fun recognize(photo: PhotoRef): String = try {
        val image = InputImage.fromFilePath(context, Uri.parse(photo.id))
        Tasks.await(client.process(image)).text
    } catch (e: Exception) {
        ""
    }
}
