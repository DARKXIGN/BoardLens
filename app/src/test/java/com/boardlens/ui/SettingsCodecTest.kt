package com.boardlens.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SettingsCodecTest {

    @Test
    fun roundTripKeepsEverythingButShowImage() {
        val s = DisplaySettings(
            fontSizeSp = 52,
            highContrast = true,
            inverted = true,
            showImage = true,
            upscale = false,
            speakNewSlides = true,
        )
        val back = SettingsCodec.decode(SettingsCodec.encode(s))
        assertEquals(s.copy(showImage = false), back)
    }

    @Test
    fun emptyStoreGivesDefaults() {
        assertEquals(DisplaySettings(), SettingsCodec.decode(emptyMap<String, Any>()))
    }

    @Test
    fun badValuesFallBackOrAreClamped() {
        val s = SettingsCodec.decode(
            mapOf(
                SettingsCodec.KEY_FONT_SIZE to 500,
                SettingsCodec.KEY_HIGH_CONTRAST to "yes",
                SettingsCodec.KEY_INVERTED to true,
            ),
        )
        assertEquals(DisplaySettings.FONT_MAX, s.fontSizeSp)
        assertFalse(s.highContrast)
        assertEquals(true, s.inverted)

        assertEquals(DisplaySettings.FONT_MIN, SettingsCodec.decode(mapOf(SettingsCodec.KEY_FONT_SIZE to 3)).fontSizeSp)
        // Off-step values snap to the nearest step.
        assertEquals(40, SettingsCodec.decode(mapOf(SettingsCodec.KEY_FONT_SIZE to 41)).fontSizeSp)
    }
}
