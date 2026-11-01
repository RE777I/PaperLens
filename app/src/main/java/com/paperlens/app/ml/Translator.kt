package com.paperlens.app.ml

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await
import java.util.Locale

/**
 * On-device translation with ML Kit. Each language has its own model (30–50 MB) that downloads once;
 * English is built in. Translation always goes through English internally, so a Chinese → Indonesian
 * translation needs the Chinese and Indonesian models.
 */
object DocTranslator {

    /** Languages ML Kit can translate, as (BCP-47 tag, display name), sorted by name. */
    val languages: List<Pair<String, String>> = TranslateLanguage.getAllLanguages()
        .map { it to displayName(it) }
        .sortedBy { it.second }

    fun displayName(tag: String): String =
        Locale.forLanguageTag(tag).getDisplayLanguage(Locale.getDefault()).replaceFirstChar { it.titlecase(Locale.getDefault()) }

    class Failure(message: String) : Exception(message)

    /**
     * Guesses the language of [text]; null when it cannot tell or ML Kit cannot translate that
     * language (the caller then asks the user to pick it).
     */
    suspend fun detect(text: String): String? {
        val client = LanguageIdentification.getClient(LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.3f).build())
        return try {
            client.identifyPossibleLanguages(text.take(2000)).await()
                .asSequence()
                .mapNotNull { TranslateLanguage.fromLanguageTag(it.languageTag) }
                .firstOrNull()
        } finally {
            client.close()
        }
    }

    /** Tags of the language models already on the phone (always includes English). */
    suspend fun downloaded(): Set<String> =
        RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java).await()
            .map { it.language }.toSet() + TranslateLanguage.ENGLISH

    suspend fun download(tag: String, wifiOnly: Boolean = false) {
        val conditions = DownloadConditions.Builder().apply { if (wifiOnly) requireWifi() }.build()
        RemoteModelManager.getInstance().download(TranslateRemoteModel.Builder(tag).build(), conditions).await()
    }

    suspend fun delete(tag: String) {
        if (tag == TranslateLanguage.ENGLISH) return
        RemoteModelManager.getInstance().deleteDownloadedModel(TranslateRemoteModel.Builder(tag).build()).await()
    }

    /**
     * Translates [text] from [sourceTag] to [targetTag], paragraph by paragraph so the layout stays and
     * each piece is within the model's limits. [onProgress] reports what is happening for the UI.
     */
    suspend fun translate(text: String, sourceTag: String, targetTag: String, onProgress: (String) -> Unit = {}): String {
        if (text.isBlank()) return text
        return translateAll(text.split(Regex("\n\\s*\n")), sourceTag, targetTag, onProgress).joinToString("\n\n")
    }

    /**
     * Translates each of [texts] (paragraphs, OCR blocks) with one model, downloading the languages
     * first if needed. Line breaks inside a piece are joined so sentences are translated whole.
     */
    suspend fun translateAll(texts: List<String>, sourceTag: String, targetTag: String, onProgress: (String) -> Unit = {}): List<String> {
        if (sourceTag == targetTag) throw Failure("The text is already in ${displayName(targetTag)}.")
        val have = runCatching { downloaded() }.getOrDefault(emptySet())
        val missing = listOf(sourceTag, targetTag).filter { it !in have }
        if (missing.isNotEmpty()) {
            onProgress("Downloading ${missing.joinToString(" and ") { displayName(it) }} (first time only)…")
            try {
                // Shows progress in the app (LanguageDownloads.active) and in a notification.
                LanguageDownloads.ensure(missing)
            } catch (e: Failure) {
                throw e
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                throw Failure("Couldn't download the language. Check the internet connection and try again. (${e.message})")
            }
        }
        val translator = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(sourceTag).setTargetLanguage(targetTag).build())
        try {
            try {
                translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            } catch (e: Exception) {
                throw Failure("Couldn't load the language model. Try again. (${e.message})")
            }
            // OCR breaks sentences into lines; join them so each sentence is translated whole.
            val joiner = if (sourceTag in NO_SPACES) "" else " "
            return texts.mapIndexed { i, piece ->
                onProgress("Translating ${i + 1} of ${texts.size}…")
                val clean = piece.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(joiner)
                if (clean.isEmpty()) "" else translator.translate(clean).await()
            }
        } finally {
            translator.close()
        }
    }

    /** Scripts written without spaces between words. */
    private val NO_SPACES = setOf(TranslateLanguage.CHINESE, TranslateLanguage.JAPANESE, TranslateLanguage.THAI)
}
