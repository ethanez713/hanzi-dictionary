package com.hanzidictionary.app.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * Speaks characters with the device's own Mandarin TTS voice. (This replaced the bundled
 * Piper syllable bank — the packaged voice's tones weren't accurate, and on-device voices
 * are far better; see spec §16.)
 *
 * Initialization is asynchronous and a device may have no Mandarin voice at all
 * (emulators often don't), so readiness is exposed as Compose state: play affordances
 * appear only once a voice is confirmed, and everything degrades to silence, never a
 * crash. App-lifetime instance; TextToSpeech.shutdown() is deliberately never called.
 *
 * Reading-level nuance: the engine reads a bare character with its most common
 * pronunciation, so the play affordance belongs on the primary (highest-frequency)
 * reading only — it would lie on the secondary readings of a 多音字.
 */
class MandarinTts(context: Context) {
    var available by mutableStateOf(false)
        private set

    private var tts: TextToSpeech? = null

    init {
        // The init listener is posted to the main looper, so when this is constructed on
        // the main thread (it is — lazily from the Application) it cannot observe `tts`
        // before the assignment below completes.
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.SIMPLIFIED_CHINESE)
                available = result == TextToSpeech.LANG_AVAILABLE ||
                    result == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
                    result == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
            }
        }
    }

    fun speak(text: String) {
        if (!available) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "hanzi")
    }
}
