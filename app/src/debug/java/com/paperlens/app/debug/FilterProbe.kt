package com.paperlens.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.paperlens.app.imaging.BitmapUtils
import com.paperlens.app.imaging.DocumentDetector
import com.paperlens.app.imaging.ImageFilter
import com.paperlens.app.imaging.ImageProcessor
import com.paperlens.app.ml.OcrScript
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.concurrent.thread

/**
 * Debug builds only: renders every filter for each JPEG in files/probe/in into files/probe/out
 * (`<name>_<FILTER>.png`), so the filters can be compared with reference images off the device.
 *
 *     adb shell am broadcast -n com.paperlens.app/.debug.FilterProbe
 */
class FilterProbe : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Plain thread (no goAsync, which is limited to about a minute): keep the app in the foreground while it runs.
        val app = context.applicationContext
        thread {
            try {
                val dir = File(app.filesDir, "probe")
                val out = File(dir, "out").apply { mkdirs() }
                val only = intent.getStringExtra("filter")
                if (intent.getStringExtra("mode") == "word") {
                    val script = intent.getStringExtra("script")?.let { OcrScript.valueOf(it) } ?: OcrScript.LATIN
                    val outDir = File(dir, "wordout").apply { mkdirs() }
                    File(dir, "word").listFiles { f -> f.name.endsWith(".jpg") }?.sortedBy { it.name }?.forEach { file ->
                        val started = System.currentTimeMillis()
                        runBlocking { WordProbe.convert(app, file, outDir, script) }
                        Log.i(TAG, "word ${file.name} ${System.currentTimeMillis() - started} ms")
                    }
                    Log.i(TAG, "done")
                    return@thread
                }
                if (intent.getStringExtra("mode") == "ocrscore") {
                    // How each script's OCR reads each page: for tuning automatic language choice.
                    File(dir, "word").listFiles { f -> f.name.endsWith(".jpg") }?.sortedBy { it.name }?.forEach { file ->
                        val bitmap = BitmapUtils.decode(file, 2400)
                        for (script in listOf(OcrScript.LATIN, OcrScript.CHINESE)) {
                            val text = runBlocking { com.paperlens.app.ml.Ocr.recognizeText(app, bitmap, script) }
                            val els = text.textBlocks.flatMap { it.lines }.flatMap { it.elements }
                            val chars = els.sumOf { it.text.length }
                            val han = els.sumOf { e -> e.text.count { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN } }
                            val conf = if (els.isEmpty()) 0f else els.map { it.confidence }.average().toFloat()
                            val weighted = els.sumOf { (it.confidence * it.text.length).toDouble() }
                            Log.i(TAG, String.format(java.util.Locale.ROOT, "ocrscore %s %s els=%d chars=%d han=%d conf=%.3f weighted=%.1f",
                                file.name, script, els.size, chars, han, conf, weighted))
                            // Per-character confidence of unsure words (print next to handwriting).
                            if (intent.getStringExtra("symbols") != null) els.filter { it.confidence < 0.5f }.forEach { e ->
                                Log.i(TAG, "symbols ${file.name} $script ${e.text} | " + e.symbols.joinToString(" ") { s ->
                                    "${s.text}:${"%.2f".format(java.util.Locale.ROOT, s.confidence)}@${s.boundingBox?.left}"
                                })
                            }
                        }
                    }
                    Log.i(TAG, "done")
                    return@thread
                }
                if (intent.getStringExtra("mode") == "detect") {
                    // Page outline per photo, as on import (photo scaled like BitmapUtils does there).
                    File(dir, "detect").listFiles { f -> f.name.endsWith(".jpg") }?.sortedBy { it.name }?.forEach { file ->
                        val photo = BitmapUtils.decode(file)
                        val outline = DocumentDetector.detectOutline(photo)
                        Log.i(TAG, "detect ${file.name} ${photo.width}x${photo.height} ${outline?.encode()?.substringBefore(';') ?: "none"}")
                        // Each finder on its own, for tuning how they are combined.
                        fun pts(q: List<android.graphics.PointF>?) = q?.joinToString(",") { "${it.x.toInt()},${it.y.toInt()}" } ?: "none"
                        val found = com.paperlens.app.imaging.CornerModel.detectPass(photo)
                        Log.i(TAG, "  model${if (found?.padded == true) "(padded)" else ""} ${pts(found?.corners)}")
                        Log.i(TAG, "  paper ${pts(com.paperlens.app.imaging.PageFinder.find(photo)?.corners)}")
                        Log.i(TAG, "  edges ${pts(DocumentDetector.detectByEdges(photo))}")
                    }
                    Log.i(TAG, "done")
                    return@thread
                }
                File(dir, "in").listFiles { f -> f.name.endsWith(".jpg") }?.sortedBy { it.name }?.forEach { file ->
                    val src = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
                    for (filter in ImageFilter.entries) {
                        if (filter == ImageFilter.ORIGINAL || (only != null && filter.name != only)) continue
                        val started = System.currentTimeMillis()
                        val result = ImageProcessor.apply(src, filter)
                        File(out, "${file.nameWithoutExtension}_${filter.name}.png").outputStream().use {
                            result.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                        Log.i(TAG, "${file.name} ${filter.name} ${src.width}x${src.height} ${System.currentTimeMillis() - started} ms")
                    }
                }
                Log.i(TAG, "done")
            } catch (t: Throwable) {
                Log.e(TAG, "failed", t)
            }
        }
    }

    private companion object {
        const val TAG = "FilterProbe"
    }
}
