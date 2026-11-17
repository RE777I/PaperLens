package com.paperlens.app.ml

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.paperlens.app.runLogged
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Downloads translation language models with visible progress.
 *
 * ML Kit has no progress callback, but it fetches each model through Android's DownloadManager (with
 * the system notification hidden), and an app can always read its own downloads back. So while a model
 * downloads we poll DownloadManager for bytes, size and why it might be waiting (no network, Wi-Fi
 * only, retrying), show that in the app through [active], and in a progress notification.
 *
 * Downloads run in an app-wide scope, so leaving the screen does not stop them.
 */
@SuppressLint("StaticFieldLeak")
object LanguageDownloads {

    data class Progress(val bytes: Long, val total: Long, val note: String) {
        /** 0..1, or null while the size is not known yet. */
        val fraction: Float? get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else null
        val sizeText: String get() = if (total > 0) "${mb(bytes)} of ${mb(total)} MB" else if (bytes > 0) "${mb(bytes)} MB" else ""
    }

    private const val CHANNEL = "language_downloads"
    private const val NOTIFICATION = 4201

    // The application context (set once in PaperLensApp), which lives as long as the app: not a leak.
    @SuppressLint("StaticFieldLeak")
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val jobs = mutableMapOf<String, Job>()

    private val activeState = MutableStateFlow<Map<String, Progress>>(emptyMap())

    /** Languages downloading right now (tag → progress). */
    val active: StateFlow<Map<String, Progress>> = activeState

    fun init(context: Context) {
        app = context.applicationContext
    }

    /** Starts downloading [tag] in the background (no-op if it is already downloading). */
    fun start(tag: String, onDone: (Throwable?) -> Unit = {}) {
        if (jobs[tag]?.isActive == true) return
        jobs[tag] = scope.launch { onDone(runCatching { download(tag) }.exceptionOrNull()) }
    }

    /** Downloads every language of [tags] that is missing, one after another, with progress. */
    suspend fun ensure(tags: Collection<String>) {
        val have = runLogged("LanguageDownloads", "Listing downloaded languages") { DocTranslator.downloaded() }.getOrDefault(emptySet())
        for (tag in tags.filter { it !in have }) {
            val running = jobs[tag]
            if (running?.isActive == true) running.join() else download(tag)
        }
        val still = runLogged("LanguageDownloads", "Listing downloaded languages") { DocTranslator.downloaded() }.getOrDefault(emptySet())
        val missing = tags.filter { it !in still }
        if (missing.isNotEmpty()) throw DocTranslator.Failure("${missing.joinToString { DocTranslator.displayName(it) }} did not finish downloading. Try again.")
    }

    /** Stops a download (removes it from the system download queue too). */
    fun cancel(tag: String) {
        jobs.remove(tag)?.cancel()
        runLogged("LanguageDownloads", "Cancelling running downloads") { ourDownloads().filter { it.running }.forEach { manager().remove(it.id) } }
        activeState.value = activeState.value - tag
        if (activeState.value.isEmpty()) NotificationManagerCompat.from(app).cancel(NOTIFICATION)
    }

    private suspend fun download(tag: String) {
        val name = DocTranslator.displayName(tag)
        activeState.value = activeState.value + (tag to Progress(0, 0, "Starting…"))
        notifyProgress(name, Progress(0, 0, "Starting…"))
        try {
            coroutineScopeDownload(tag, name)
            notifyDone("$name is ready for translation", null)
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) {
                NotificationManagerCompat.from(app).cancel(NOTIFICATION)
            } else {
                notifyDone("Couldn't download $name", e.message ?: "Check the internet connection and try again.")
            }
            throw e
        } finally {
            activeState.value = activeState.value - tag
        }
    }

    /** Runs the ML Kit download while watching DownloadManager for progress. */
    private suspend fun coroutineScopeDownload(tag: String, name: String) = kotlinx.coroutines.coroutineScope {
        val work = async { DocTranslator.download(tag) }
        while (isActive && !work.isCompleted) {
            val p = withContext(Dispatchers.IO) { readProgress() }
            if (p != null) {
                activeState.value = activeState.value + (tag to p)
                notifyProgress(name, p)
            }
            delay(500)
        }
        work.await()
    }

    // ---- DownloadManager ---------------------------------------------------------------------------

    private class Row(val id: Long, val status: Int, val reason: Int, val bytes: Long, val total: Long) {
        val running get() = status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PENDING || status == DownloadManager.STATUS_PAUSED
    }

    private fun manager() = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    /** This app's downloads (only ML Kit models: the app downloads nothing else this way). */
    private fun ourDownloads(): List<Row> {
        val rows = ArrayList<Row>()
        manager().query(DownloadManager.Query()).use { c ->
            val id = c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
            val status = c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
            val reason = c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
            val bytes = c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val total = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            while (c.moveToNext()) rows += Row(c.getLong(id), c.getInt(status), c.getInt(reason), c.getLong(bytes), c.getLong(total))
        }
        return rows
    }

    private fun readProgress(): Progress? {
        val running = runLogged("LanguageDownloads", "Reading download progress") { ourDownloads() }.getOrNull()?.filter { it.running } ?: return null
        if (running.isEmpty()) return Progress(0, 0, "Preparing…")
        val bytes = running.sumOf { it.bytes.coerceAtLeast(0) }
        val total = if (running.all { it.total > 0 }) running.sumOf { it.total } else -1
        val paused = running.firstOrNull { it.status == DownloadManager.STATUS_PAUSED }
        val note = when {
            paused == null && running.any { it.status == DownloadManager.STATUS_RUNNING } -> "Downloading"
            paused == null -> "Waiting to start"
            paused.reason == DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "Waiting for an internet connection"
            paused.reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "Waiting for Wi-Fi"
            paused.reason == DownloadManager.PAUSED_WAITING_TO_RETRY -> "Connection problem, retrying"
            else -> "Paused"
        }
        return Progress(bytes, total, note)
    }

    // ---- notification ------------------------------------------------------------------------------

    private fun canNotify(): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun channel() {
        if (Build.VERSION.SDK_INT >= 26) {
            app.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Translation downloads", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progress of translation language downloads"
                },
            )
        }
    }

    @SuppressLint("MissingPermission") // checked in canNotify()
    private fun notifyProgress(name: String, p: Progress) {
        if (!canNotify()) return
        channel()
        val fraction = p.fraction
        val text = listOf(p.sizeText, p.note).filter { it.isNotEmpty() }.joinToString(" · ")
        val n = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading $name for translation")
            .setContentText(text)
            .setProgress(1000, ((fraction ?: 0f) * 1000).toInt(), fraction == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(app.packageManager.getLaunchIntentForPackage(app.packageName)?.let {
                android.app.PendingIntent.getActivity(app, 0, it, android.app.PendingIntent.FLAG_IMMUTABLE)
            })
            .build()
        NotificationManagerCompat.from(app).notify(NOTIFICATION, n)
    }

    @SuppressLint("MissingPermission")
    private fun notifyDone(title: String, text: String?) {
        if (!canNotify()) return
        channel()
        val n = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(if (text == null) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .apply { if (text != null) setContentText(text) }
            .setAutoCancel(true)
            .setTimeoutAfter(if (text == null) 8000 else 0)
            .build()
        NotificationManagerCompat.from(app).notify(NOTIFICATION, n)
    }

    private fun mb(bytes: Long) = String.format(Locale.getDefault(), "%.1f", bytes / 1_048_576.0)
}
