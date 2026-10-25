package com.boardlens.ui.theme

import android.content.Context
import android.graphics.RectF
import android.util.Log
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.caverock.androidsvg.SVG
import java.util.concurrent.ConcurrentHashMap

/**
 * The only icon set in the app: Tossface emoji by Toss Team.
 *
 * The files in assets/tossface are the original SVGs from github.com/toss/tossface, copied
 * byte-for-byte. The Tossface license does not allow modified versions (a format change
 * counts), so they are drawn as SVG at runtime instead of being converted to vector
 * drawables. Attribution and license: Licenses screen and assets/licenses/.
 */
enum class TossIcon(val file: String) {
    Speaker("u1F50A"),        // 🔊 read aloud
    Stop("u23F9"),            // ⏹ stop reading
    Pause("u23F8"),           // ⏸ freeze
    Play("u25B6"),            // ▶ resume
    Gear("u2699"),            // ⚙ options
    Close("u2716"),           // ✖ close
    Back("u2B05"),            // ⬅ back / cancel
    Magnifier("u1F50D"),      // 🔍 enlarge the camera
    Return("u21A9"),          // ↩ shrink / reset
    Ruler("u1F4D0"),          // 📐 select or adjust the board
    Target("u1F3AF"),         // 🎯 auto-detect
    Plus("u2795"),            // ➕
    Minus("u2796"),           // ➖
    Contrast("u1F313"),       // 🌓 high contrast
    Arrows("u1F504"),         // 🔄 invert colours
    Picture("u1F5BC"),        // 🖼 show image
    Sparkles("u2728"),        // ✨ sharpen (upscale)
    Screen("u1F5A5"),         // 🖥 whole view
    Chart("u1F4CA"),          // 📊 evaluation
    Check("u2705"),           // ✅ board found
    Warning("u26A0"),         // ⚠ lost / problem
    Camera("u1F4F7"),         // 📷 camera permission
    Loudspeaker("u1F4E2"),    // 📢 read new slides aloud
    Page("u1F4C4"),           // 📄 licenses
    Hand("u270B"),            // ✋ hold still
    Clipboard("u1F4CB"),      // 📋 copy text
}

private object TossfaceCache {
    private val svgs = ConcurrentHashMap<TossIcon, SVG>()

    fun get(context: Context, icon: TossIcon): SVG? = svgs[icon] ?: try {
        context.assets.open("tossface/${icon.file}.svg").use { SVG.getFromInputStream(it) }
            .also { svgs[icon] = it }
    } catch (e: Exception) {
        Log.e("BoardLens", "Tossface ${icon.file} failed to load", e)
        null
    }
}

/** A Tossface icon. Decorative: the control it sits in carries the label. */
@Composable
fun TossIconImage(icon: TossIcon, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    val context = LocalContext.current.applicationContext
    val svg = remember(icon) { TossfaceCache.get(context, icon) }
    Spacer(
        modifier
            .size(size)
            .clearAndSetSemantics { }
            .drawBehind {
                val doc = svg ?: return@drawBehind
                drawIntoCanvas { canvas ->
                    doc.renderToCanvas(canvas.nativeCanvas, RectF(0f, 0f, this.size.width, this.size.height))
                }
            },
    )
}
