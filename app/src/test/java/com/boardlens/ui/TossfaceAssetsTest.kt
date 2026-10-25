package com.boardlens.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * The Tossface license allows redistributing the original files only: no modified versions,
 * and a format change or added metadata counts as one. This test fails if a bundled SVG
 * differs from the original by even one byte, or if the code uses an icon that is not
 * listed (and hash-pinned) in tools/tossface/manifest.txt.
 *
 * Fix a failure with: powershell -ExecutionPolicy Bypass -File tools\tossface\fetch_tossface.ps1
 */
class TossfaceAssetsTest {

    // Gradle runs unit tests with the module directory (app/) as working directory.
    private val assets = File("src/main/assets/tossface")
    private val manifest = File("../tools/tossface/manifest.txt")
    private val iconSource = File("src/main/java/com/boardlens/ui/theme/Tossface.kt")

    private fun pinned(): Map<String, String> = manifest.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .associate { line ->
            val (name, hash) = line.split(Regex("\\s+"))
            name to hash.lowercase()
        }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    @Test
    fun bundledSvgsAreTheUnmodifiedOriginals() {
        val expected = pinned()
        val files = assets.listFiles { f -> f.extension == "svg" }.orEmpty()
        assertTrue("no Tossface SVGs found in $assets", files.isNotEmpty())
        val changed = files.filter { sha256(it) != expected[it.nameWithoutExtension] }.map { it.name }
        assertEquals("modified or unlisted Tossface files", emptyList<String>(), changed)
    }

    @Test
    fun everyIconTheAppUsesIsBundledAndPinned() {
        val used = Regex("\"(u[0-9A-F]{4,5}(?:_u[0-9A-F]{4,5})*)\"").findAll(iconSource.readText())
            .map { it.groupValues[1] }.toSet()
        assertTrue("found no icon names in $iconSource", used.isNotEmpty())
        val expected = pinned()
        val missing = used.filter { it !in expected || !File(assets, "$it.svg").isFile }
        assertEquals("icons used in code but not bundled/pinned", emptyList<String>(), missing.sorted())
    }
}
