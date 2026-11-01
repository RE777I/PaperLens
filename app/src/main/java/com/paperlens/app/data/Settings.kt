package com.paperlens.app.data

import com.paperlens.app.S
import com.paperlens.app.R

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.paperlens.app.imaging.ImageFilter
import com.paperlens.app.ml.OcrScript
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

enum class PdfPageSize(private val labelRes: Int) { A4(R.string.a4), LETTER(R.string.letter), FIT(R.string.fit_to_image) ;
    val label: String get() = S.str(labelRes)
}

/** ML Kit scanner feature sets. */
enum class ScannerMode(private val labelRes: Int, private val descriptionRes: Int) {
    FULL(R.string.full, R.string.crop_filters_and_clean_erase_stains_fingers),
    FILTERS(R.string.filters, R.string.crop_and_filters_no_clean_tool),
    BASIC(R.string.basic, R.string.crop_only_fastest),
;
    val label: String get() = S.str(labelRes)

    val description: String get() = S.str(descriptionRes)
}

/** Light / dark: follow the phone, or always one of them. */
enum class ThemeMode(private val labelRes: Int) { SYSTEM(R.string.system_default), LIGHT(R.string.light), DARK(R.string.dark) ;
    val label: String get() = S.str(labelRes)
}

/** Accent colours for buttons, tabs and highlights: (light-theme colour, dark-theme colour). */
enum class AccentColor(private val labelRes: Int, val light: Long, val dark: Long) {
    BLUE(R.string.blue, 0xFF1565C0, 0xFFA9C7FF),
    TEAL(R.string.teal, 0xFF00796B, 0xFF80CBC4),
    PURPLE(R.string.purple, 0xFF6750A4, 0xFFD0BCFF),
    GREEN(R.string.green, 0xFF2E7D32, 0xFFA5D6A7),
    ORANGE(R.string.orange, 0xFFD84315, 0xFFFFB68A),
    ROSE(R.string.rose, 0xFFC2185B, 0xFFFFB0C8),
;
    val label: String get() = S.str(labelRes)
}

enum class ExportQuality(private val labelRes: Int, val maxDimension: Int, val jpegQuality: Int) {
    HIGH(R.string.high_original, Int.MAX_VALUE, 92),
    MEDIUM(R.string.medium, 2000, 75),
    LOW(R.string.small_file, 1400, 60),
;
    val label: String get() = S.str(labelRes)
}

data class AppSettings(
    val defaultFilter: ImageFilter = ImageFilter.MAGIC_COLOR,
    val pdfPageSize: PdfPageSize = PdfPageSize.A4,
    val exportQuality: ExportQuality = ExportQuality.HIGH,
    /** Exported PDFs carry the recognised text hidden behind each scan, so they can be searched and copied. */
    val searchablePdf: Boolean = true,
    val ocrScript: OcrScript = OcrScript.AUTO,
    /** Non-Latin scripts found on this person's pages so far; automatic OCR tries them on every page. */
    val ocrScriptsUsed: Set<OcrScript> = emptySet(),
    val translateTarget: String = "en",
    val appLock: Boolean = false,
    val autoOcr: Boolean = false,
    /** Detect and flatten the page in photos imported from the gallery or shared from other apps. */
    val autoCrop: Boolean = true,
    /** Open the scanner straight away when the app starts. */
    val startWithCamera: Boolean = false,
    /** Show the gallery button inside the scanner. */
    val scannerGallery: Boolean = true,
    /** Also copy every scanned page to the phone gallery (Pictures/PaperLens). */
    val saveToGallery: Boolean = false,
    val scannerMode: ScannerMode = ScannerMode.FULL,
    /** Use the Google (ML Kit) document scanner instead of the in-app camera with modes. */
    val useGoogleScanner: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accent: AccentColor = AccentColor.BLUE,
    /** Android 12+: colours taken from the wallpaper (Material You) instead of [accent]. */
    val dynamicColor: Boolean = false,
    /** Dark theme with pure black backgrounds (saves battery on OLED screens). */
    val pureBlack: Boolean = false,
)

class SettingsStore(private val context: Context) {
    private object Keys {
        val filter = stringPreferencesKey("default_filter")
        val pageSize = stringPreferencesKey("pdf_page_size")
        val quality = stringPreferencesKey("export_quality")
        val searchablePdf = booleanPreferencesKey("searchable_pdf")
        /** Before automatic OCR: the one script chosen (Latin by default). */
        val ocrScriptOld = stringPreferencesKey("ocr_script")
        val ocrScript = stringPreferencesKey("ocr_language")
        val ocrScriptsUsed = stringSetPreferencesKey("ocr_scripts_used")
        val translateTarget = stringPreferencesKey("translate_target")
        val appLock = booleanPreferencesKey("app_lock")
        val autoOcr = booleanPreferencesKey("auto_ocr")
        val autoCrop = booleanPreferencesKey("auto_crop")
        val startWithCamera = booleanPreferencesKey("start_with_camera")
        val scannerGallery = booleanPreferencesKey("scanner_gallery")
        val saveToGallery = booleanPreferencesKey("save_to_gallery")
        val scannerMode = stringPreferencesKey("scanner_mode")
        val useGoogleScanner = booleanPreferencesKey("use_google_scanner")
        val themeMode = stringPreferencesKey("theme_mode")
        val accent = stringPreferencesKey("accent")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val pureBlack = booleanPreferencesKey("pure_black")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs[Keys.filter] = next.defaultFilter.name
            prefs[Keys.pageSize] = next.pdfPageSize.name
            prefs[Keys.quality] = next.exportQuality.name
            prefs[Keys.searchablePdf] = next.searchablePdf
            prefs[Keys.ocrScript] = next.ocrScript.name
            prefs[Keys.ocrScriptsUsed] = next.ocrScriptsUsed.map { it.name }.toSet()
            prefs[Keys.translateTarget] = next.translateTarget
            prefs[Keys.appLock] = next.appLock
            prefs[Keys.autoOcr] = next.autoOcr
            prefs[Keys.autoCrop] = next.autoCrop
            prefs[Keys.startWithCamera] = next.startWithCamera
            prefs[Keys.scannerGallery] = next.scannerGallery
            prefs[Keys.saveToGallery] = next.saveToGallery
            prefs[Keys.scannerMode] = next.scannerMode.name
            prefs[Keys.useGoogleScanner] = next.useGoogleScanner
            prefs[Keys.themeMode] = next.themeMode.name
            prefs[Keys.accent] = next.accent.name
            prefs[Keys.dynamicColor] = next.dynamicColor
            prefs[Keys.pureBlack] = next.pureBlack
        }
    }

    private fun Preferences.toSettings(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            defaultFilter = this[Keys.filter]?.let { ImageFilter.fromName(it) } ?: defaults.defaultFilter,
            pdfPageSize = this[Keys.pageSize]?.let { name -> PdfPageSize.entries.firstOrNull { it.name == name } }
                ?: defaults.pdfPageSize,
            exportQuality = this[Keys.quality]?.let { name -> ExportQuality.entries.firstOrNull { it.name == name } }
                ?: defaults.exportQuality,
            searchablePdf = this[Keys.searchablePdf] ?: defaults.searchablePdf,
            // A non-Latin script chosen before automatic OCR existed stays chosen; Latin (the old default) becomes Automatic.
            ocrScript = (this[Keys.ocrScript] ?: this[Keys.ocrScriptOld]?.takeIf { it != OcrScript.LATIN.name })
                ?.let { name -> OcrScript.entries.firstOrNull { it.name == name } } ?: defaults.ocrScript,
            ocrScriptsUsed = (this[Keys.ocrScriptsUsed] ?: setOfNotNull(this[Keys.ocrScriptOld]))
                .mapNotNull { name -> OcrScript.entries.firstOrNull { it.name == name } }
                .filter { it != OcrScript.LATIN && it != OcrScript.AUTO }.toSet(),
            translateTarget = this[Keys.translateTarget] ?: defaults.translateTarget,
            appLock = this[Keys.appLock] ?: defaults.appLock,
            autoOcr = this[Keys.autoOcr] ?: defaults.autoOcr,
            autoCrop = this[Keys.autoCrop] ?: defaults.autoCrop,
            useGoogleScanner = this[Keys.useGoogleScanner] ?: defaults.useGoogleScanner,
            startWithCamera = this[Keys.startWithCamera] ?: defaults.startWithCamera,
            scannerGallery = this[Keys.scannerGallery] ?: defaults.scannerGallery,
            saveToGallery = this[Keys.saveToGallery] ?: defaults.saveToGallery,
            scannerMode = this[Keys.scannerMode]?.let { name -> ScannerMode.entries.firstOrNull { it.name == name } }
                ?: defaults.scannerMode,
            themeMode = this[Keys.themeMode]?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } } ?: defaults.themeMode,
            accent = this[Keys.accent]?.let { name -> AccentColor.entries.firstOrNull { it.name == name } } ?: defaults.accent,
            dynamicColor = this[Keys.dynamicColor] ?: defaults.dynamicColor,
            pureBlack = this[Keys.pureBlack] ?: defaults.pureBlack,
        )
    }
}
