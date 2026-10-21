package com.boardlens.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Thin wrapper over the platform TTS engine (on-device voices where installed). */
class Speaker(context: Context, private val locale: Locale = Locale.US) : AutoCloseable {

    @Volatile
    private var ready = false

    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) tts.language = locale
        }
    }

    fun speak(text: String) {
        if (!ready || text.isBlank()) return
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "boardlens-read")
    }

    fun stop() {
        if (ready) tts.stop()
    }

    override fun close() {
        tts.stop()
        tts.shutdown()
    }
}
