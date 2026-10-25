package com.boardlens.ui

import android.content.Context

/**
 * Converts display settings to and from stored key/value pairs. Pure, so the rules
 * (what is kept, defaults, clamping) are unit-tested without Android.
 *
 * "Show image" is a debug view and is deliberately not kept across launches.
 */
object SettingsCodec {
    const val KEY_FONT_SIZE = "font_size_sp"
    const val KEY_HIGH_CONTRAST = "high_contrast"
    const val KEY_INVERTED = "inverted"
    const val KEY_UPSCALE = "upscale"
    const val KEY_SPEAK_NEW_SLIDES = "speak_new_slides"

    fun encode(s: DisplaySettings): Map<String, Any> = mapOf(
        KEY_FONT_SIZE to s.fontSizeSp,
        KEY_HIGH_CONTRAST to s.highContrast,
        KEY_INVERTED to s.inverted,
        KEY_UPSCALE to s.upscale,
        KEY_SPEAK_NEW_SLIDES to s.speakNewSlides,
    )

    /** Missing or wrongly typed values fall back to the defaults; text size is clamped and snapped to the step. */
    fun decode(stored: Map<String, *>): DisplaySettings {
        val d = DisplaySettings()
        fun bool(key: String, default: Boolean) = stored[key] as? Boolean ?: default
        val size = (stored[KEY_FONT_SIZE] as? Int)?.let(DisplaySettings::clampFontSize) ?: d.fontSizeSp
        return d.copy(
            fontSizeSp = size,
            highContrast = bool(KEY_HIGH_CONTRAST, d.highContrast),
            inverted = bool(KEY_INVERTED, d.inverted),
            upscale = bool(KEY_UPSCALE, d.upscale),
            speakNewSlides = bool(KEY_SPEAK_NEW_SLIDES, d.speakNewSlides),
        )
    }
}

/**
 * Keeps display settings across launches. SharedPreferences rather than DataStore: the
 * file is tiny and reading it synchronously at start-up means the first frame already has
 * the user's palette and text size (no flash of the default theme).
 */
class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("display_settings", Context.MODE_PRIVATE)

    fun load(): DisplaySettings = SettingsCodec.decode(prefs.all)

    fun save(settings: DisplaySettings) {
        val editor = prefs.edit()
        for ((key, value) in SettingsCodec.encode(settings)) {
            when (value) {
                is Int -> editor.putInt(key, value)
                is Boolean -> editor.putBoolean(key, value)
            }
        }
        editor.apply()
    }
}
