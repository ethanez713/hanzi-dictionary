package com.hanzidictionary.app.audio

import android.content.Context
import android.media.MediaPlayer
import org.json.JSONObject

/**
 * Plays a character's isolated reading from the bundled syllable bank (spec §9). Maps a
 * pinyin reading string (as stored in the pack, e.g. "qǐng") straight to a clip key via
 * the generated audio-index.json, so no tone-stripping is reimplemented on-device.
 *
 * Clips are stored uncompressed in assets (see noCompress "wav") so they open as asset
 * file descriptors. One-shot MediaPlayer per tap, released on completion — fine for
 * ~0.5s clips at the tap rate a dictionary sees.
 */
class SyllableAudioPlayer(context: Context) {
    private val appContext = context.applicationContext
    private val index: Map<String, String> by lazy { loadIndex() }

    private fun loadIndex(): Map<String, String> = try {
        val json = appContext.assets.open("audio-index.json").bufferedReader().use { it.readText() }
        val obj = JSONObject(json)
        buildMap { for (k in obj.keys()) put(k, obj.getString(k)) }
    } catch (e: Exception) {
        emptyMap()
    }

    /** True if a clip exists for this reading (so the UI can show a play affordance). */
    fun hasClip(readingValue: String): Boolean = index.containsKey(readingValue)

    fun play(readingValue: String) {
        val key = index[readingValue] ?: return
        try {
            val afd = appContext.assets.openFd("audio/$key.wav")
            val mp = MediaPlayer()
            afd.use { mp.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            mp.setOnCompletionListener { it.release() }
            mp.setOnErrorListener { player, _, _ -> player.release(); true }
            mp.prepare()
            mp.start()
        } catch (e: Exception) {
            // missing/corrupt clip: silently no-op rather than crash a lookup
        }
    }
}
