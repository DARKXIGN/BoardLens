package com.boardlens.eval

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.boardlens.pipeline.Enhancer
import com.boardlens.pipeline.Frame
import com.boardlens.pipeline.Rectifier
import com.boardlens.pipeline.StageTimer
import com.boardlens.pipeline.TextRecognizer
import org.json.JSONObject
import java.io.File

data class EvalSummary(val sessions: Int, val frames: Int, val predictions: Int, val failures: Int)

/**
 * Re-runs saved captures through rectify -> enhance -> OCR once per enhancer variant,
 * so every variant sees identical input. Writes preds.jsonl in each session folder
 * (overwriting the previous run), which `bl-eval` scores against ground truth.
 *
 * OCR runs on the phone on purpose: ML Kit is the recognizer the app ships with, and
 * its numbers are the ones that matter. Run from the analysis thread with the live
 * pipeline paused.
 */
class Evaluator(
    private val rectifier: Rectifier,
    private val variants: List<Enhancer>,
    private val recognizerFactory: () -> TextRecognizer,
) {

    fun run(sessions: List<File>, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): EvalSummary {
        val work = sessions.associateWith { dir ->
            File(dir, EvalStore.FRAMES).readLines().filter { it.isNotBlank() }.map { JSONObject(it) }
        }
        val total = work.values.sumOf { it.size } * variants.size
        var done = 0
        var predictions = 0
        var failures = 0

        val recognizer = recognizerFactory()
        try {
            for ((dir, records) in work) {
                // Multi-frame enhancers fuse earlier frames of the same session, in capture order.
                val history = variants.associateWith { ArrayDeque<Bitmap>() }
                File(dir, EvalStore.PREDS).bufferedWriter().use { out ->
                    for (rec in records) {
                        val bmp = BitmapFactory.decodeFile(File(dir, rec.getString("file")).path)
                        if (bmp == null) {
                            failures++
                            done += variants.size
                            onProgress(done, total)
                            continue
                        }
                        val quadJson = rec.getJSONArray("quad")
                        val quad = quadFromFlatList(List(quadJson.length()) { quadJson.getDouble(it) })

                        val rectTimer = StageTimer()
                        val rectified = rectTimer.measure("rectify") { rectifier.rectify(Frame(bmp, 0L), quad) }

                        for (variant in variants) {
                            val timer = StageTimer()
                            val past = history.getValue(variant)
                            val enhanced = timer.measure("enhance") { variant.enhance(rectified, past.toList()) }
                            val text = timer.measure("ocr") { recognizer.recognize(enhanced).fullText }
                            if (variant.historySize > 0) {
                                past.addLast(rectified)
                                while (past.size > variant.historySize) past.removeFirst()
                            }
                            out.write(
                                JsonLine.encode(
                                    linkedMapOf(
                                        "id" to rec.getString("id"),
                                        "variant" to variant.id,
                                        "distance_m" to rec.getDouble("distance_m"),
                                        "note" to rec.optString("note"),
                                        "session" to rec.optString("session"),
                                        "pred" to text,
                                        "ocr_input_px" to listOf(enhanced.width, enhanced.height),
                                        "ms" to rectTimer.timings + timer.timings,
                                    ),
                                ),
                            )
                            out.write("\n")
                            predictions++
                            done++
                            onProgress(done, total)
                        }
                    }
                }
            }
        } finally {
            recognizer.close()
        }
        return EvalSummary(work.size, work.values.sumOf { it.size }, predictions, failures)
    }
}
