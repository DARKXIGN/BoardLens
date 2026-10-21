package com.boardlens.eval

import android.graphics.Bitmap
import com.boardlens.pipeline.Frame
import com.boardlens.pipeline.Quad
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Evaluation captures on disk, under the app's external files dir:
 *
 *     eval/<yyyyMMdd-HHmmss>_d<distance>m/
 *         f000.png ...      board crop (lossless: exactly what the app saw, minus surroundings)
 *         frames.jsonl      one record per crop: id, distance, note, quad in crop pixels, ...
 *         preds.jsonl       written by [Evaluator]: one record per (frame, enhancer variant)
 *
 * Pull to a computer with:
 *     adb pull /sdcard/Android/data/com.boardlens/files/eval
 */
class EvalStore(val root: File) {

    fun sessions(): List<File> =
        root.listFiles { f -> f.isDirectory && File(f, FRAMES).isFile }?.sortedBy { it.name } ?: emptyList()

    fun newSession(distanceM: Double, note: String): EvalSession {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dir = File(root, sessionName(stamp, distanceM))
        check(dir.mkdirs() || dir.isDirectory) { "Could not create $dir" }
        return EvalSession(dir, distanceM, note)
    }

    /** Deletes one session folder (only ever inside [root]). */
    fun delete(sessionId: String): Boolean {
        val dir = File(root, sessionId)
        if (dir.parentFile?.canonicalPath != root.canonicalPath) return false
        return dir.deleteRecursively()
    }

    companion object {
        const val FRAMES = "frames.jsonl"
        const val PREDS = "preds.jsonl"
    }
}

class EvalSession(val dir: File, val distanceM: Double, val note: String) {
    val id: String get() = dir.name

    var saved = 0
        private set

    /**
     * Saves the board crop of [frame] with the board corners [quadPx] (frame pixels).
     * [extra] adds fields such as tracking status or sharpness. Returns the record id.
     * Call from one thread (the analysis thread).
     */
    fun save(frame: Frame, quadPx: Quad, extra: Map<String, Any?> = emptyMap()): String {
        val bmp = frame.bitmap
        val crop = cropAround(quadPx, bmp.width, bmp.height)
        val name = "f%03d".format(saved)
        val cropped = Bitmap.createBitmap(bmp, crop.left, crop.top, crop.width, crop.height)
        try {
            File(dir, "$name.png").outputStream().use { out ->
                check(cropped.compress(Bitmap.CompressFormat.PNG, 100, out)) { "PNG encode failed" }
            }
        } finally {
            if (cropped !== bmp) cropped.recycle()
        }

        val recordId = "$id/$name"
        val record = linkedMapOf<String, Any?>(
            "id" to recordId,
            "session" to this.id,
            "file" to "$name.png",
            "distance_m" to distanceM,
            "note" to note,
            "quad" to quadPx.relativeTo(crop).toFlatList(),
            "crop" to listOf(crop.left, crop.top, crop.width, crop.height),
            "frame_size" to listOf(bmp.width, bmp.height),
            "timestamp_ns" to frame.timestampNs,
        )
        record.putAll(extra)
        File(dir, EvalStore.FRAMES).appendText(JsonLine.encode(record) + "\n")
        saved++
        return recordId
    }
}
