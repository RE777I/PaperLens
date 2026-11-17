package com.paperlens.app.imaging

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.net.Uri
import android.util.Log
import com.paperlens.app.R
import com.paperlens.app.S
import com.paperlens.app.runLogged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.nio.FloatBuffer
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/** What a corner model outputs: one heatmap per corner (DocAligner style), or the 8 corner coordinates. */
enum class CornerOutput { HEATMAPS, POINTS }

/**
 * A page-corner model the app can run: the built-in one, [CornerModel.NONE] (line and colour finding
 * only), or an `.onnx` file the user imported. Corners come out as TL, TR, BR, BL.
 */
data class CornerModelInfo(
    val id: String,
    val name: String,
    val bytes: Long,
    val builtIn: Boolean,
    val output: CornerOutput = CornerOutput.HEATMAPS,
    val inputWidth: Int = 256,
    val inputHeight: Int = 256,
    /** Input laid out as 1 × H × W × 3 instead of 1 × 3 × H × W. */
    val channelsLast: Boolean = false,
    /** Channel order RGB instead of BGR (DocAligner was trained on BGR). */
    val rgb: Boolean = false,
    /** On the phone and ready (false: the built-in model before it is downloaded). */
    val available: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("bytes", bytes).put("output", output.name)
        .put("w", inputWidth).put("h", inputHeight).put("nhwc", channelsLast).put("rgb", rgb)

    companion object {
        fun fromJson(o: JSONObject) = CornerModelInfo(
            id = o.getString("id"),
            name = o.getString("name"),
            bytes = o.optLong("bytes"),
            builtIn = false,
            output = runLogged("CornerModel", "Output kind of an imported model") { CornerOutput.valueOf(o.getString("output")) }.getOrDefault(CornerOutput.HEATMAPS),
            inputWidth = o.optInt("w", 256),
            inputHeight = o.optInt("h", 256),
            channelsLast = o.optBoolean("nhwc"),
            rgb = o.optBoolean("rgb"),
        )
    }
}

/**
 * Learned page-corner detector, run with ONNX Runtime. The built-in model is DocAligner's FastViT-SA24
 * heatmap model (Apache-2.0, github.com/DocsaidLab/DocAligner). It is not in the APK (that saved 42 MB):
 * the authors' published file (83 MB) is downloaded on first use — on its own over Wi-Fi, or when asked
 * for in Settings — and checked against its SHA-256. Until then pages are found by line and colour
 * finding. Phones that had the earlier built-in copy (fp16, from assets) keep using it.
 *
 * DocAligner looks at the whole photo at 256 × 256 and outputs one 128 × 128 heatmap per corner
 * (top-left, top-right, bottom-right, bottom-left). It copes with tilt, perspective, busy desks and
 * low contrast far better than line finding: on SmartDoc 2015 frames it reaches a mean IoU of 0.99,
 * where the edge detector manages 0.16.
 *
 * Users can pick another model in Settings: none (line and colour finding only; lightest), or an
 * imported `.onnx` file with one image input and either 4 heatmaps or 8 coordinates as output. The
 * list of imported models and the choice live in `files/corner_models/models.json`.
 */
object CornerModel {

    private const val TAG = "CornerModel"
    /** The copy earlier versions extracted from the APK (kept in no-backup storage). */
    private const val LEGACY_FILE = "docaligner_fastvit_sa24_fp16.onnx"
    private const val MODEL_FILE = "docaligner_fastvit_sa24_fp32.onnx"
    /** DocsaidLab's own download of the model (the link their `docaligner` package uses). */
    private const val DOWNLOAD_URL = "https://drive.usercontent.google.com/download?id=14vUH77v6yGg7zFctUgcT6BzV5Iisg4Dl&export=download&confirm=t"
    const val DOWNLOAD_BYTES = 83_084_930L
    private const val DOWNLOAD_SHA256 = "7f9f5a8935b2eb22b3ee0245d34996063f54562df390d34714af2d76928695bc"
    private const val DIR = "corner_models"
    private const val INDEX = "models.json"

    const val BUILT_IN = "docaligner"
    const val NONE = "none"

    /** A corner whose heatmap peak is below this is treated as "no document in view". */
    private const val PEAK_THRESHOLD = 0.3f

    /** Margin around the photo for the second, square look (fraction of its long side, each side). */
    private const val PAD = 0.05f

    @Volatile private var appContext: Context? = null
    private val env by lazy { OrtEnvironment.getEnvironment() }
    private val lock = Any()

    /** The loaded model; replaced when the user picks another. */
    private var loaded: Pair<CornerModelInfo, OrtSession>? = null

    @Volatile var selectedId: String = BUILT_IN
        private set

    /** The built-in model's file once it is on the phone (an earlier version's copy, or the download). */
    private fun builtInFile(context: Context): File? =
        File(context.noBackupFilesDir, LEGACY_FILE).takeIf { it.length() > 0 }
            ?: File(context.noBackupFilesDir, MODEL_FILE).takeIf { it.length() == DOWNLOAD_BYTES }

    private fun builtInInfo(context: Context): CornerModelInfo {
        val file = builtInFile(context)
        return CornerModelInfo(
            id = BUILT_IN,
            name = S.str(R.string.model_docaligner),
            bytes = file?.length() ?: DOWNLOAD_BYTES,
            builtIn = true,
            available = file != null,
        )
    }

    private fun noneInfo() = CornerModelInfo(NONE, S.str(R.string.model_edges_only), 0L, builtIn = true)

    /** Download of the built-in model: idle, running (bytes so far of the total), or failed. */
    sealed class Download {
        data object Idle : Download()
        data class Running(val done: Long, val total: Long) : Download()
        data class Failed(val reason: String) : Download()
    }

    private val downloadState = MutableStateFlow<Download>(Download.Idle)
    val download: StateFlow<Download> = downloadState

    /** Started on its own at most once per app run (Wi-Fi only); a failure then waits for the user. */
    @Volatile private var autoTried = false

    /** Remembers the context and loads the chosen model in the background so the first scan is not slowed. */
    fun init(context: Context) {
        appContext = context.applicationContext
        selectedId = readIndex(context).optString("selected", BUILT_IN)
        Thread({ session() }, "corner-model-load").apply { isDaemon = true }.start()
    }

    /** Every model to choose from: built in, none, then the imported ones. */
    fun available(context: Context): List<CornerModelInfo> = listOf(builtInInfo(context), noneInfo()) + imported(context)

    /**
     * Fetches the built-in model in the background (no-op if it is there or already downloading). Written to
     * a temporary file, checked against the published SHA-256, then moved into place; the next detection
     * loads it.
     */
    fun downloadBuiltIn(context: Context) {
        val app = context.applicationContext
        if (builtInFile(app) != null) return
        synchronized(lock) {
            if (downloadState.value is Download.Running) return
            downloadState.value = Download.Running(0, DOWNLOAD_BYTES)
        }
        Thread({
            // Below the screen's threads: an 83 MB download and its checksum must not make the app stutter.
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            val part = File(app.noBackupFilesDir, "$MODEL_FILE.part")
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                val connection = (URL(DOWNLOAD_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                }
                try {
                    if (connection.responseCode != HttpURLConnection.HTTP_OK) error("HTTP ${connection.responseCode}")
                    connection.inputStream.use { input ->
                        part.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            var shown = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                digest.update(buf, 0, n)
                                done += n
                                if (done - shown > 512 * 1024) {
                                    shown = done
                                    downloadState.value = Download.Running(done, DOWNLOAD_BYTES)
                                }
                            }
                        }
                    }
                } finally {
                    connection.disconnect()
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                if (part.length() != DOWNLOAD_BYTES || hash != DOWNLOAD_SHA256) error("the file did not match (${part.length()} bytes)")
                if (!part.renameTo(File(app.noBackupFilesDir, MODEL_FILE))) error("could not save the file")
                synchronized(lock) {
                    loaded?.second?.close()
                    loaded = null
                }
                downloadState.value = Download.Idle
                Log.i(TAG, "Built-in corner model downloaded")
            } catch (t: Throwable) {
                part.delete()
                Log.w(TAG, "Corner model download failed", t)
                downloadState.value = Download.Failed(t.message ?: t.javaClass.simpleName)
            }
        }, "corner-model-download").apply { isDaemon = true }.start()
    }

    /** Starts the download on its own when the phone is on an unmetered network (Wi-Fi), once per app run. */
    private fun downloadIfFree(context: Context) {
        if (autoTried) return
        autoTried = true
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) downloadBuiltIn(context)
    }

    fun selected(context: Context): CornerModelInfo = available(context).firstOrNull { it.id == selectedId } ?: builtInInfo(context)

    /** Switches model; the next detection loads it. */
    fun select(context: Context, id: String) {
        synchronized(lock) {
            if (available(context).none { it.id == id }) return
            selectedId = id
            writeIndex(context, readIndex(context).put("selected", id))
            loaded?.second?.close()
            loaded = null
        }
        Thread({ session() }, "corner-model-load").apply { isDaemon = true }.start()
    }

    /**
     * Copies an `.onnx` file into the app, checks it loads, works out its input size and output kind,
     * and runs it once. Returns the new model (not yet selected) or the reason it cannot be used.
     */
    fun import(context: Context, uri: Uri, name: String): Result<CornerModelInfo> = runCatching {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val id = "m_" + UUID.randomUUID().toString().take(8)
        val file = File(dir, "$id.onnx")
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
                ?: error("Could not open the file")
            val info = env.createSession(file.path, OrtSession.SessionOptions()).use { s -> describe(s, id, name, file.length()) }
            env.createSession(file.path, OrtSession.SessionOptions()).use { s ->
                // A plain grey picture must run through without errors.
                val grey = Bitmap.createBitmap(info.inputWidth, info.inputHeight, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
                run(s, info, grey)
            }
            synchronized(lock) {
                val index = readIndex(context)
                val list = index.optJSONArray("imported") ?: JSONArray()
                list.put(info.toJson())
                writeIndex(context, index.put("imported", list))
            }
            info
        } catch (t: Throwable) {
            file.delete()
            throw IllegalArgumentException(t.message ?: "Not a usable corner model", t)
        }
    }

    /** Removes an imported model (the built-in one stays); falls back to the built-in model if it was in use. */
    fun delete(context: Context, id: String) {
        synchronized(lock) {
            val index = readIndex(context)
            val list = index.optJSONArray("imported") ?: JSONArray()
            val kept = JSONArray()
            for (i in 0 until list.length()) list.getJSONObject(i).let { if (it.getString("id") != id) kept.put(it) }
            index.put("imported", kept)
            if (selectedId == id) {
                selectedId = BUILT_IN
                index.put("selected", BUILT_IN)
                loaded?.second?.close()
                loaded = null
            }
            writeIndex(context, index)
            File(File(context.filesDir, DIR), "$id.onnx").delete()
        }
    }

    /** Switches an imported model between RGB and BGR input (for models trained the other way). */
    fun setRgb(context: Context, id: String, rgb: Boolean) {
        synchronized(lock) {
            val index = readIndex(context)
            val list = index.optJSONArray("imported") ?: return
            for (i in 0 until list.length()) list.getJSONObject(i).let { if (it.getString("id") == id) it.put("rgb", rgb) }
            writeIndex(context, index)
            if (loaded?.first?.id == id) {
                loaded?.second?.close()
                loaded = null
            }
        }
    }

    /**
     * Corners in the order the model reports them (TL, TR, BR, BL), in [src] pixels, or null when no
     * model is chosen, it failed to load, or it sees no document.
     */
    fun detect(src: Bitmap): List<PointF>? = detectPass(src)?.corners

    /** Corners found by the model, and whether it took the second (padded, coarser) look to find them. */
    class Found(val corners: List<PointF>, val padded: Boolean)

    fun detectPass(src: Bitmap): Found? {
        val (info, session) = session() ?: return null
        // The whole photo stretched to the model's square input is what DocAligner was trained on and is the
        // most precise. A tall phone photo squeezed that way, or a page touching the photo's edge, is often
        // missed; a second look on a square canvas with a margin finds those (on the sample signs and
        // pages: 12 of 16 found instead of 1, with SmartDoc accuracy unchanged).
        detect(session, info, src, padded = false)?.let { return Found(it, false) }
        return detect(session, info, src, padded = true)?.let { Found(it, true) }
    }

    private fun detect(session: OrtSession, info: CornerModelInfo, src: Bitmap, padded: Boolean): List<PointF>? {
        val iw = info.inputWidth
        val ih = info.inputHeight
        // Input picture and where the photo sits in it: input = photo * scale + offset.
        val sx: Float; val sy: Float; val ox: Float; val oy: Float
        val input: Bitmap
        if (!padded) {
            sx = iw.toFloat() / src.width; sy = ih.toFloat() / src.height; ox = 0f; oy = 0f
            // A plain bilinear resize, like the one the model was trained with (shrinking here loses pages it found).
            input = Bitmap.createScaledBitmap(src, iw, ih, true)
        } else {
            val side = max(src.width, src.height) * (1f + 2 * PAD)
            sx = iw / side; sy = ih / side
            ox = (iw - src.width * sx) / 2; oy = (ih - src.height * sy) / 2
            val photo = shrink(src, max(1, Math.round(src.width * sx)), max(1, Math.round(src.height * sy)))
            input = Bitmap.createBitmap(iw, ih, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(input)
            canvas.drawColor(Color.BLACK)
            canvas.drawBitmap(photo, ox, oy, null)
            if (photo !== src) photo.recycle()
        }
        val out = try {
            run(session, info, input)
        } finally {
            if (input !== src) input.recycle()
        }
        val inInput = when (info.output) {
            CornerOutput.HEATMAPS -> {
                val (values, shape) = out
                val n = shape.size
                val mh = shape[n - 2].toInt()
                val mw = shape[n - 1].toInt()
                List(4) { k -> peak(values, k * mh * mw, mw, mh, iw, ih) ?: return null }
            }
            CornerOutput.POINTS -> {
                val v = out.first
                // Normalised (0..1) or in input pixels.
                val normalised = v.take(8).all { it in -0.5f..1.5f }
                List(4) { i ->
                    PointF(if (normalised) v[2 * i] * iw else v[2 * i], if (normalised) v[2 * i + 1] * ih else v[2 * i + 1])
                }
            }
        }
        return inInput.map { PointF((it.x - ox) / sx, (it.y - oy) / sy) }
    }

    private fun session(): Pair<CornerModelInfo, OrtSession>? = synchronized(lock) {
        loaded?.let { return@synchronized it }
        val context = appContext ?: return@synchronized null
        val info = selected(context)
        if (info.id == NONE) return@synchronized null
        val file = if (info.builtIn) {
            // Not downloaded yet: line and colour finding meanwhile.
            builtInFile(context) ?: run {
                downloadIfFree(context)
                return@synchronized null
            }
        } else {
            File(File(context.filesDir, DIR), "${info.id}.onnx")
        }
        runCatching {
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(min(4, Runtime.getRuntime().availableProcessors()))
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            (info to env.createSession(file.path, options)).also { loaded = it }
        }.onFailure { Log.w(TAG, "Corner model ${info.name} unavailable, using edge detection only", it) }.getOrNull()
    }

    /** Input size, layout and output kind of a freshly imported model. */
    private fun describe(s: OrtSession, id: String, name: String, bytes: Long): CornerModelInfo {
        require(s.inputInfo.size == 1) { "The model must take one image as input (it takes ${s.inputInfo.size})" }
        val input = s.inputInfo.values.first().info as? TensorInfo ?: error("The model input is not an image tensor")
        val ishape = input.shape
        require(ishape.size == 4) { "The model input must be 1 × 3 × H × W or 1 × H × W × 3" }
        val channelsLast = ishape[3] == 3L && ishape[1] != 3L
        require(channelsLast || ishape[1] == 3L) { "The model input must have 3 colour channels" }
        val h = (if (channelsLast) ishape[1] else ishape[2]).toInt().takeIf { it > 0 } ?: 256
        val w = (if (channelsLast) ishape[2] else ishape[3]).toInt().takeIf { it > 0 } ?: 256
        val output = s.outputInfo.values.firstNotNullOfOrNull { node ->
            val shape = (node.info as? TensorInfo)?.shape ?: return@firstNotNullOfOrNull null
            when {
                shape.size == 4 && shape[1] == 4L -> CornerOutput.HEATMAPS
                shape.isNotEmpty() && shape.all { it > 0 } && shape.fold(1L) { a, b -> a * b } == 8L -> CornerOutput.POINTS
                else -> null
            }
        } ?: error("The model must output 4 heatmaps (1 × 4 × H × W) or 8 corner coordinates")
        return CornerModelInfo(id, name, bytes, builtIn = false, output = output, inputWidth = w, inputHeight = h,
            channelsLast = channelsLast, rgb = output == CornerOutput.POINTS)
    }

    /** Runs the model on [src] (stretched to its input size) and returns the first matching output with its shape. */
    private fun run(session: OrtSession, info: CornerModelInfo, src: Bitmap): Pair<FloatArray, LongArray> {
        val iw = info.inputWidth
        val ih = info.inputHeight
        val input = Bitmap.createScaledBitmap(src, iw, ih, true)
        val px = IntArray(iw * ih)
        input.getPixels(px, 0, iw, 0, 0, iw, ih)
        if (input !== src) input.recycle()
        // Scaled to 0..1, in the channel order the model was trained with.
        val plane = iw * ih
        val data = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val c = px[i]
            val first = if (info.rgb) Color.red(c) else Color.blue(c)
            val last = if (info.rgb) Color.blue(c) else Color.red(c)
            if (info.channelsLast) {
                data[3 * i] = first / 255f; data[3 * i + 1] = Color.green(c) / 255f; data[3 * i + 2] = last / 255f
            } else {
                data[i] = first / 255f; data[plane + i] = Color.green(c) / 255f; data[2 * plane + i] = last / 255f
            }
        }
        val shape = if (info.channelsLast) longArrayOf(1L, ih.toLong(), iw.toLong(), 3L) else longArrayOf(1L, 3L, ih.toLong(), iw.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape).use { tensor ->
            session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                for (i in 0 until result.size()) {
                    val t = result.get(i) as? OnnxTensor ?: continue
                    val oshape = t.info.shape
                    val match = when (info.output) {
                        CornerOutput.HEATMAPS -> oshape.size == 4 && oshape[1] == 4L
                        CornerOutput.POINTS -> oshape.fold(1L) { a, b -> a * b } == 8L
                    }
                    if (!match) continue
                    val buffer = t.floatBuffer
                    return FloatArray(buffer.remaining()).also { buffer.get(it) } to oshape
                }
            }
        }
        error("The model gave no corner output")
    }

    /**
     * [src] at [w] × [h], averaging the pixels it drops: halving steps first, since one bilinear step from a
     * 12 MP photo to 256 px samples a few pixels and turns text and texture into noise the model misreads.
     */
    private fun shrink(src: Bitmap, w: Int, h: Int): Bitmap {
        var cur = src
        while (cur.width >= 2 * w && cur.height >= 2 * h) {
            val next = Bitmap.createScaledBitmap(cur, cur.width / 2, cur.height / 2, true)
            if (cur !== src) cur.recycle()
            cur = next
        }
        if (cur.width == w && cur.height == h) return cur
        return Bitmap.createScaledBitmap(cur, w, h, true).also { if (cur !== src) cur.recycle() }
    }

    /** Weighted centre of the strongest blob of one heatmap (at [offset] in [map]), scaled to the photo. */
    private fun peak(map: FloatArray, offset: Int, w: Int, h: Int, width: Int, height: Int): PointF? {
        var best = -1f
        var by = 0
        var bx = 0
        for (y in 0 until h) for (x in 0 until w) {
            val v = map[offset + y * w + x]
            if (v > best) {
                best = v; by = y; bx = x
            }
        }
        if (best < PEAK_THRESHOLD) return null
        val floor = max(PEAK_THRESHOLD, best * 0.5f)
        val r = max(w, h) / 8
        var sum = 0.0
        var sx = 0.0
        var sy = 0.0
        for (y in max(0, by - r)..min(h - 1, by + r)) for (x in max(0, bx - r)..min(w - 1, bx + r)) {
            val v = map[offset + y * w + x]
            if (v >= floor) {
                sum += v; sx += v * x; sy += v * y
            }
        }
        return PointF(((sx / sum + 0.5) * width / w).toFloat(), ((sy / sum + 0.5) * height / h).toFloat())
    }

    private fun readIndex(context: Context): JSONObject {
        val f = File(File(context.filesDir, DIR), INDEX)
        return runLogged("CornerModel", "Reading the model list") { JSONObject(f.readText()) }.getOrElse { JSONObject() }
    }

    private fun writeIndex(context: Context, o: JSONObject) {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val tmp = File(dir, "$INDEX.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(File(dir, INDEX))
    }

    private fun imported(context: Context): List<CornerModelInfo> {
        val list = readIndex(context).optJSONArray("imported") ?: return emptyList()
        return (0 until list.length()).mapNotNull { runLogged("CornerModel", "An imported model's entry") { CornerModelInfo.fromJson(list.getJSONObject(it)) }.getOrNull() }
    }
}
