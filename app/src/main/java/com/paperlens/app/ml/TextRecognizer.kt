package com.paperlens.app.ml

import com.paperlens.app.R
import com.paperlens.app.S

import android.content.Context
import android.net.Uri
import com.google.android.gms.common.api.OptionalModuleApi
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate.InstallState
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.paperlens.app.app
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizerOptionsInterface
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.paperlens.app.runLogged
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.tasks.await
import java.io.File

/**
 * Scripts supported by on-device ML Kit OCR. Latin is bundled in the APK; the others are Play services
 * modules downloaded the first time they are used.
 */
enum class OcrScript(private val labelRes: Int) {
    /** Each page is read with Latin and the other scripts this person uses; the best reading wins (see [Ocr]). */
    AUTO(R.string.ocr_automatic),
    LATIN(R.string.ocr_latin),
    CHINESE(R.string.chinese),
    JAPANESE(R.string.japanese),
    KOREAN(R.string.korean),
    DEVANAGARI(R.string.ocr_devanagari),
    ;

    val label: String get() = S.str(labelRes)

    companion object {
        /** The OCR model that reads [languageTag] (Latin for everything the Latin model covers). */
        fun forLanguage(languageTag: String): OcrScript = when (languageTag.substringBefore('-')) {
            "zh" -> CHINESE
            "ja" -> JAPANESE
            "ko" -> KOREAN
            "hi", "mr", "ne", "sa" -> DEVANAGARI
            else -> LATIN
        }
    }

    /** Characters only this script's model reads (Latin has none: every model reads Latin). */
    fun owns(c: Char): Boolean = when (this) {
        CHINESE -> Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN
        JAPANESE -> Character.UnicodeScript.of(c.code).let {
            it == Character.UnicodeScript.HAN || it == Character.UnicodeScript.HIRAGANA || it == Character.UnicodeScript.KATAKANA
        }
        KOREAN -> Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HANGUL
        DEVANAGARI -> Character.UnicodeScript.of(c.code) == Character.UnicodeScript.DEVANAGARI
        AUTO, LATIN -> false
    }

    fun options(): TextRecognizerOptionsInterface = when (this) {
        AUTO, LATIN -> TextRecognizerOptions.DEFAULT_OPTIONS
        CHINESE -> ChineseTextRecognizerOptions.Builder().build()
        JAPANESE -> JapaneseTextRecognizerOptions.Builder().build()
        KOREAN -> KoreanTextRecognizerOptions.Builder().build()
        DEVANAGARI -> DevanagariTextRecognizerOptions.Builder().build()
    }
}

/**
 * On-device OCR. With [OcrScript.AUTO] each page is read with Latin and with every other script this
 * person uses (remembered from earlier pages, the phone's languages, or any model already on the
 * phone). Every model also reads Latin letters, so a non-Latin reading wins only when it reads clearly
 * more (confidence × characters, 5 % more) and found at least [OWN_CHARS] characters of its own script;
 * otherwise Latin wins. Measured on the sample pages: Chinese pages 8–544 Han characters, English
 * pages 0–2. A script that wins is remembered; models missing from the phone are fetched in the
 * background for next time rather than waited for.
 */
object Ocr {
    suspend fun recognize(context: Context, image: File, script: OcrScript): String =
        process(context, InputImage.fromFilePath(context, Uri.fromFile(image)), script).textBlocks.joinToString("\n\n") { it.text }

    /** The full ML Kit result for [bitmap]: blocks, lines and words with boxes and confidence. */
    suspend fun recognizeText(context: Context, bitmap: android.graphics.Bitmap, script: OcrScript): Text =
        process(context, InputImage.fromBitmap(bitmap, 0), script)

    /** Reads [input] in [script], or in the best script for it when [script] is [OcrScript.AUTO]. */
    suspend fun process(context: Context, input: InputImage, script: OcrScript): Text {
        if (script != OcrScript.AUTO) return read(context, input, script, wait = true)
        val latin = read(context, input, OcrScript.LATIN, wait = false)
        val settings = context.app.settings
        val wanted = (settings.current().ocrScriptsUsed + phoneScripts(context)) - OcrScript.LATIN - OcrScript.AUTO
        val others = OcrScript.entries.filter { it != OcrScript.AUTO && it != OcrScript.LATIN }
        val ready = others.filter { isInstalled(context, it) }
        // Wanted models not on the phone yet: fetch them for next time instead of making this page wait.
        (wanted - ready.toSet()).forEach { requestDownload(context, it) }
        var best = latin
        var bestScore = score(latin)
        var bestScript = OcrScript.LATIN
        for (s in ready) {
            val text = runLogged("Ocr", "Reading with the $s model") { read(context, input, s, wait = false) }.getOrNull() ?: continue
            val own = text.textBlocks.sumOf { b -> b.text.count(s::owns) }
            val sc = score(text)
            if (own >= OWN_CHARS && sc >= bestScore * 1.05f) {
                best = text; bestScore = sc; bestScript = s
            }
        }
        if (bestScript != OcrScript.LATIN && bestScript !in settings.current().ocrScriptsUsed) {
            settings.update { it.copy(ocrScriptsUsed = it.ocrScriptsUsed + bestScript) }
        }
        return best
    }

    /** How much was read, and how surely: Σ confidence × characters over the words. */
    private fun score(text: Text): Float =
        text.textBlocks.sumOf { b -> b.lines.sumOf { l -> l.elements.sumOf { (it.confidence * it.text.length).toDouble() } } }.toFloat()

    private suspend fun read(context: Context, input: InputImage, script: OcrScript, wait: Boolean): Text {
        val recognizer = TextRecognition.getClient(script.options())
        return try {
            if (script != OcrScript.LATIN && wait) ensureModuleInstalled(context, recognizer)
            recognizer.process(input).await()
        } finally {
            recognizer.close()
        }
    }

    /** Scripts of the languages set on the phone (e.g. Chinese for a phone set to 中文). */
    private fun phoneScripts(context: Context): Set<OcrScript> {
        val locales = context.resources.configuration.locales
        return (0 until locales.size()).map { OcrScript.forLanguage(locales[it].language) }.toSet()
    }

    private suspend fun isInstalled(context: Context, script: OcrScript): Boolean {
        if (script == OcrScript.LATIN || script == OcrScript.AUTO) return true
        val recognizer = TextRecognition.getClient(script.options())
        return try {
            runLogged("Ocr", "Checking whether a text model is installed") { ModuleInstall.getClient(context).areModulesAvailable(recognizer).await().areModulesAvailable() }.getOrDefault(false)
        } finally {
            recognizer.close()
        }
    }

    /** Starts fetching [script]'s model without waiting for it. */
    private fun requestDownload(context: Context, script: OcrScript) {
        val recognizer = TextRecognition.getClient(script.options())
        runCatching {
            ModuleInstall.getClient(context).installModules(ModuleInstallRequest.newBuilder().addApi(recognizer).build())
                .addOnCompleteListener { recognizer.close() }
        }.onFailure {
            android.util.Log.w("Ocr", "Requesting a text model download", it)
            recognizer.close()
        }
    }

    /** A non-Latin reading must contain at least this many characters of its own script to win. */
    private const val OWN_CHARS = 5

    /**
     * Unbundled models are fetched by Play services. Waits for the download instead of failing with
     * "waiting for the text optional module to be downloaded" on first use.
     */
    private suspend fun ensureModuleInstalled(context: Context, api: OptionalModuleApi) {
        val client = ModuleInstall.getClient(context)
        if (client.areModulesAvailable(api).await().areModulesAvailable()) return
        val done = CompletableDeferred<Unit>()
        val listener = InstallStatusListener { update ->
            when (update.installState) {
                InstallState.STATE_COMPLETED -> done.complete(Unit)
                InstallState.STATE_FAILED, InstallState.STATE_CANCELED ->
                    done.completeExceptionally(IllegalStateException("OCR model download failed (${update.errorCode})"))
            }
        }
        val request = ModuleInstallRequest.newBuilder().addApi(api).setListener(listener).build()
        try {
            if (client.installModules(request).await().areModulesAlreadyInstalled()) return
            // The completion callback is not always delivered (e.g. when another request started the same
            // download), so also poll availability, and give up instead of spinning forever offline.
            val ready = withTimeoutOrNull(MODULE_TIMEOUT_MS) {
                while (!done.isCompleted) {
                    if (client.areModulesAvailable(api).await().areModulesAvailable()) return@withTimeoutOrNull true
                    select<Unit> {
                        done.onAwait { }
                        onTimeout(POLL_MS) { }
                    }
                }
                done.await()
                true
            }
            if (ready == null) throw IllegalStateException("The OCR model for this language is still downloading. Check the internet connection and try again.")
        } finally {
            client.unregisterListener(listener)
        }
    }

    private const val MODULE_TIMEOUT_MS = 180_000L
    private const val POLL_MS = 2_000L
}
