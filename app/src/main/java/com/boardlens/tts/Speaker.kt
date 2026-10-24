package com.boardlens.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Thin wrapper over the platform TTS engine (on-device voices where installed).
 * [speaking] lets the UI turn "Read aloud" into "Stop" while speech is playing.
 */
class Speaker(context: Context, private val locale: Locale = Locale.US) : AutoCloseable {

    @Volatile
    private var ready = false

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private lateinit var tts: TextToSpeech

    // Callbacks arrive on a TTS binder thread; StateFlow is safe to set from any thread.
    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            _speaking.value = true
        }

        override fun onDone(utteranceId: String?) {
            _speaking.value = false
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            _speaking.value = false
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            _speaking.value = false
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            _speaking.value = false
        }
    }

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts.language = locale
                tts.setOnUtteranceProgressListener(progress)
            }
        }
    }

    fun speak(text: String) {
        if (!ready || text.isBlank()) return
        // Show "Stop" right away rather than after the engine warms up.
        _speaking.value = true
        val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        if (result != TextToSpeech.SUCCESS) _speaking.value = false
    }

    fun stop() {
        if (ready) tts.stop()
        _speaking.value = false
    }

    override fun close() {
        tts.stop()
        tts.shutdown()
        _speaking.value = false
    }

    private companion object {
        const val UTTERANCE_ID = "boardlens-read"
    }
}
