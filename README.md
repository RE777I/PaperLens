# PaperLens

An Android document scanner: scan, enhance, OCR, translate, sign and export to PDF, Word, JPG and long image.
Everything runs on the device. There are no accounts, ads or trackers.

PaperLens is an independent implementation built on Google ML Kit and open-source libraries.

## App layout

Four tabs, laid out like a typical scanner app:

| Tab | Contents |
|---|---|
| **Home** | Search, 8 quick tools (Smart Scan, PDF Tools, Import Images/Files, ID Cards, Extract Text, Photo Translation, All), Recents with Share / To Word / View on the newest document |
| **Files** | Import Files / Import Images / Create Folder, folders with rename/delete, All · Folders · Documents filter, sort, list/grid view, multi-select (share, move to folder, merge, delete) |
| **Tools** | Every tool grouped as Scan, Import, Convert, Edit, Utilities |
| **Me** | Library stats, settings, app lock, printer settings, help, about (no account) |

## Features

| Area | What you get |
|---|---|
| Scanning | ML Kit Document Scanner: live edge detection, auto-capture, perspective crop, stain/finger cleaning ("Smart Erase"), multi-page batch, gallery import; imported photos are auto-cropped and straightened. The in-app camera finds the page with a learned corner model (DocAligner, see below), then shows a crop screen with draggable corners and a magnifier. Gallery imports fall back to paper-colour and line finding; a page filling the photo except a strip of desk or hand is cropped like a scanner app does |
| Modes | Document, Book (splits two-page spreads), ID card (front + back on one A4 sheet at real size), Whiteboard, Extract Text, Photo Translation, Timestamp, QR/barcode |
| Convert | To Word creates a Word document next to the scan (opened for editing, not shared): the page layout is rebuilt like the original — headings at their size and weight, coloured text, ruled tables with merged cells, shaded-row tables, lines across the page, and photos, logos and QR codes cut out where they were (inline, inside table cells, or floating). Wrapped lines join into flowing paragraphs (no spaces between Chinese/Japanese lines, first-line indents kept); handwriting (notes, filled-in answers) is kept as small pictures of itself inside its sentence, and plain shading (shadows, tinted panels) is not mistaken for pictures. It reads the unfiltered scan, so faint table lines survive (word/PageToWord.kt, word/PageAnalysis.kt). To Excel creates an Excel document next to the scan (opened in the sheet viewer): ruled tables keep their grid, merged cells, borders and shading; tables of shaded rows and borderless tables (timetables, price lists, nutrition labels) get their columns from the gaps in the text, side-by-side tables split at a divider line, and wrapped cell text stays in one cell. Header rows are bold (frozen for long tables), and amounts become real numbers that add up — thousands separators and decimals in either style (1,234.50 / 1.234,50), percentages, currency (Rp, $, ¥…) and dates. Light text on dark boards (green price boards) is read too (excel/PageToExcel.kt, excel/SheetBuilder.kt). PDF to images, PDF to long image |
| Word documents | Word (.docx) files live in the library with a page preview and a W badge: from To Word, Import Files, or “Open with” / Share from other apps. They open as a page (pinch to zoom) and can be edited in place: tap text to type; bold / italic / underline, size, colour, alignment, lists, Title / Heading styles; insert paragraphs, tables (rows and columns, merged cells kept), pictures and page breaks; undo / redo. Changes stay unsaved until Save, and leaving asks to Save or Discard. Imported files keep their headers, footers and styles when saved. Share as .docx or text |
| Excel documents | Excel (.xlsx) files live in the library with a sheet preview and an X badge: from To Excel, Import Files, or “Open with” / Share from other apps. They open as a grid like Excel: sheet tabs, column letters and row numbers, column widths and row heights, merged cells, fonts, fills, borders, alignment, frozen panes, and numbers, percentages, currency and dates shown in their number format; scroll with fling and pinch to zoom. Tap a cell to see its content (or formula); tap it again, or double-tap the cell, to edit. Edits stay unsaved until Save, which writes only the changed cells into the file (charts, pictures and other sheets are kept; Excel recalculates formulas on open). Share as .xlsx or text |
| Page tools | Long-press pages in a document to select several, then edit (the editor's Filter applies to the selected pages; it can also be switched to This page or All pages), rotate, move (to the start, the end or after a page), share or delete them; extract pages into a new document, reorder pages, compress PDF, lock document |
| Editing | Every change to an open document (Edit, Crop, Sign, Add Text, filters, page moves…) is a preview first: the document shows *Changes not saved yet* with Save / Discard, and Back asks before leaving (the preview survives the app being closed). Every page quietly keeps its original, uncropped photo (camera shots, imports, Add → Scan more pages, Retake, and through Add Text / Sign / drawings); Crop (page viewer, long-press a page, or Edit) shows that whole photo so the crop can be lined up again — after markup it warns that re-cropping starts from the original. Add Text has text colour and opacity, bold, italic, alignment, a background colour with its own opacity, size, and drag to place |
| Camera | Live page outline steadied across frames; Auto capture ("Hold still…", a ring fills round the shutter) shoots once the page is still and then waits for the next page, like vFlat's auto-shoot. Batch shots fly into the tray and are cropped in the background; single shots open a crop screen. Crop screens have corner handles plus a handle in the middle of each side that moves the whole side (its corners slide along the neighbouring sides), a magnifier, and magnetic snapping: handles jump onto corners and straight edges found in the photo (with a haptic tick) |
| Books | Open books, notebooks and pages held with a finger are found by their paper colour and flattened: the curled top and bottom edges are straightened (perspective first, then a Coons patch between the curved sides) |
| Enhance | Original, Lighten, Enhance, Magic Pro, No Shadow, B&W, Eco, Grayscale, Invert, fitted to a scanner app's full-resolution results on 17 photos (rawScreenshot/Filter: documents, signs, a receipt, a screen, packaging, a dark object). A paper-colour estimate removes shadows and colour casts but is not trusted on dark or strongly coloured areas, so packaging and signs keep their colours; thin strokes are deepened; No Shadow leaves non-paper photos untouched, as a scanner app does (imaging/FilterCurves.kt). Rotate; per page or whole document, always non-destructive |
| Translate on the page | Document → More → Translate, the page viewer's Translate, or Tools → Photo Translation: every page is shown with each paragraph covered in its own background colour and the translation written in place (original ink colour, size shrunk to fit), like a camera translator. Press and hold a page to see the original; save as a new document, share the pages or copy the text |
| Translate (text) | Full-screen Text / Translate view: From (auto-detect or chosen) ⇄ To with a searchable language list, download and progress messages, readable errors, copy / share. More Settings → Translation Languages lists the downloaded language models (delete, download more, default target) |
| Appearance | Me → Appearance: System / Light / Dark, AMOLED black, wallpaper colours (Material You, Android 12+) and six accent colours |
| App language | The app's own text is in English, Indonesian and Simplified Chinese (`res/values`, `values-in`, `values-zh`). It follows the phone's language, or on Android 13+ a language chosen just for PaperLens (Settings → Apps → PaperLens → Language) |
| Find Text | Document → More → Find Text: a search bar like Ctrl+F in Word. Every match is highlighted on the page images (the current one in orange), a counter (“3/20”) and ← / → arrows step through matches across pages, turning to the page and zooming onto the match. Case-insensitive; works for Chinese/Japanese (no word spaces) as well as Latin text. Text already recognised on a page is reused; pages without it are read first (with progress). Back closes the search and stays on the page on screen. Word positions are saved with each page, so a page is read only once: searching it again highlights at once |
| Text | On-device OCR (Latin built in; Chinese, Japanese, Korean, Devanagari downloaded on first use; changing the OCR language re-reads pages). The OCR language defaults to **Automatic**: each page is read with Latin and with the other scripts this person uses (remembered from earlier pages, the phone's languages, or models already on the phone), and the clearly better reading wins, so To Word, Find Text, Edit Text, To Excel and searchable PDFs handle Chinese and English pages without changing Settings. Missing models download in the background for next time; on-page text editing that keeps the original size and colours, copy/share, on-device translation (50+ languages) |
| Edit | Handwritten signatures (saved and reusable, drag/pinch to place), pen and highlighter annotation, text watermark |
| Library | Search by title **and text inside scans**, folders, merge documents, reorder / move / delete pages, rename |
| Export | PDF (A4 / Letter / fit, three quality levels, optional password), save PDF to device, JPG images, long image, TXT, Word (.docx, converted with the layout and shared directly), print. PDFs are **searchable**: the recognised text is hidden behind each scan (invisible text, any script including Chinese), so PDF readers can search, select and copy it (Settings → Share & Export → Searchable PDF, on by default). Pages imported from a PDF and left unchanged are exported as the original PDF pages |
| Imported PDFs | Each page keeps its original PDF page alongside its picture. Zooming redraws the visible part from the PDF, so small print stays crisp at any zoom; the PDF's own text is stored at import, so Find Text and Edit Text work at once without OCR. Changing the picture (filter, rotation, re-crop, retake, signature or other marks) makes it a picture page; going back to Original without rotation brings the PDF back |
| Import | Images, PDFs (each page becomes an editable page), "Share to PaperLens" from any app |
| Security | App lock and per-document lock with fingerprint / face / device PIN. Phone backups to the cloud keep only the settings, never scans or the library (`res/xml/data_extraction_rules.xml`, `backup_rules.xml`); moving to a new phone by cable or Wi-Fi takes everything. Libraries come only from Google, JetBrains, Microsoft and Maven Central, have no known vulnerabilities (OSV, 2026-11-02), and add no permissions beyond fingerprint for the app lock |
| Accessibility | Every button and switch has a name for TalkBack in the app's language: icon-only buttons, the search bar, each document's select box ("Select <title>"), and settings switches, which toggle as one row with their title |
| QR / barcodes | Camera scan, or read every code in a saved photo (Me → Code history → image button); history of scanned codes. **Create Code** (Tools) makes a QR code from text, a link or a Wi-Fi network, to show or share as a picture |
| Tools tab | Every tool that works on a document opens a picker that lists only the documents the tool can use, with a search box once the list is long and **Scan new** / **Import file** shortcuts that carry straight on into the tool. **Compress** shows the PDF's size at each quality before you choose, then shares it or saves it to the device; pages imported from a PDF are turned into pictures only when that is smaller, so the result never grows. **Page Numbers** draws a number on every page (four positions, three styles, any first number) |
| Shortcuts | Long-press the launcher icon → *Scan document*, *Scan QR code* |

## Releases

Every version is published on the repository's **Releases** page (GitHub → PaperLens → Releases) with signed APKs:
`PaperLens-<version>-arm64-v8a.apk` for almost every phone, `armeabi-v7a` for older 32-bit phones and `x86_64` for emulators.
To be notified of new ones, use **Watch → Custom → Releases** on the repository page.

To publish a new version (needs `gh auth login` once and the signing setup below):

```powershell
.\scripts\release.ps1 -Version 1.1.0 -Notes "What changed"
```

The script refuses to run with uncommitted changes, bumps `versionName`/`versionCode`, builds the signed release APKs, tags `v<version>`, pushes, and creates the GitHub release with the APKs attached. Release builds shrink the code with R8; libraries that look up classes by name need keep rules in `app/proguard-rules.pro` (PdfBox, ONNX Runtime).

## Build

Requirements: Android Studio with Android Gradle Plugin 9.5 support (JDK 17 or newer) and Android SDK 37
(the build compiles against Android 17 and targets Android 15).

1. Open this folder in Android Studio and let it sync. It downloads Gradle 9.8 and creates the wrapper.
2. Run the `app` configuration on a device with Google Play services. The document scanner, code scanner
   and the non-Latin OCR models are delivered by Play services.

Command line, once the wrapper exists:

```bash
./gradlew assembleDebug
```

The debug APK is large (~140 MB): it is unminified and carries native libraries for every ABI.

### Release build

`assembleRelease` runs R8 (minify + resource shrinking) and writes one APK per ABI to
`app/build/outputs/apk/release/`:

| APK | Size |
|---|---|
| `app-arm64-v8a-release.apk` | ~36 MB (most phones) |
| `app-armeabi-v7a-release.apk` | ~26 MB (older 32-bit phones) |
| `app-x86_64-release.apk` | ~37 MB (emulators, Chromebooks) |

For Google Play, upload `./gradlew bundleRelease` (`app/build/outputs/bundle/release/app-release.aab`)
instead: Play splits it per ABI and screen density on its own.

What keeps it small:

- **ABI splits.** ML Kit Translate (~17 MB), OCR (~11 MB) and language ID (~1 MB) ship native code per ABI.
  32-bit x86 is not built.
- **On-demand OCR models.** Latin OCR is bundled so it works offline from the first launch. Chinese,
  Japanese, Korean and Devanagari use the Play services variants: the model downloads the first time the
  script is used (`Ocr.ensureModuleInstalled`), which needs Google Play services and a network connection once.
- **No BouncyCastle post-quantum tables.** `org/bouncycastle/pqc/**` resources (~8 MB) are excluded.
  PdfBox only needs the classic ciphers for password-protected PDFs.
- **Pinned transitive libraries.** `app/build.gradle.kts` raises BouncyCastle (from PdfBox) to 1.86 and Guava (from
  ML Kit and CameraX) to 33.7.2, because the versions they bring have published vulnerabilities. Check the whole
  runtime classpath with `./gradlew :app:dependencies --configuration releaseRuntimeClasspath` against
  [OSV](https://osv.dev) after changing libraries.

#### Signing

The release signing key is never committed (`*.jks` and `*.keystore` are git-ignored). Create a local
keystore once:

```bash
keytool -genkeypair -v -keystore ~/.android/paperlens-release.jks -storetype PKCS12 -alias paperlens -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=PaperLens Local Release"
```

Then point the build at it in `local.properties` (already git-ignored):

```properties
release.storeFile=C:/Users/<you>/.android/paperlens-release.jks
release.storePassword=...
release.keyAlias=paperlens
release.keyPassword=...
```

or with the environment variables `PAPERLENS_STORE_FILE`, `PAPERLENS_STORE_PASSWORD`,
`PAPERLENS_KEY_ALIAS` and `PAPERLENS_KEY_PASSWORD`. A relative `storeFile` is resolved from the project root.
Without any of these, `assembleRelease` still builds, but the APKs are unsigned and cannot be installed.

A release build has a different signature from a debug build, so uninstall the debug app first
(`adb uninstall com.paperlens.app`). Then install the split for your device:

```bash
adb install app/build/outputs/apk/release/app-arm64-v8a-release.apk
```

## Project layout

```
app/src/main/java/com/paperlens/app/
├── data/        Room entities + DAO, DocumentRepository (files + DB), DataStore settings
├── imaging/     ImageProcessor filters, PageComposer (ID card, book split, watermark, long image, overlays)
├── ml/          ML Kit OCR and translation wrappers
├── export/      PDF/JPG/TXT/DOCX export (PdfBox-Android), PDF import, share & print
├── security/    Biometric app lock
└── ui/          Jetpack Compose screens: home, document, page, signature/annotate, ID card, QR, settings
```

## Not yet implemented

These need a server, an account system or heavier on-device models:

- Cloud sync, accounts, teams, cloud-drive upload
- Fax, e-signature workflows with other signers
- Searchable PDF text layer, Excel/PPT conversion, table recognition
- AI chat over documents, formula and homework solving, object counting, invoice parsing
- AI photo restoration, moiré removal, watermark removal

## License

PaperLens is free software under the **GNU General Public License, version 3 or later** (see
[LICENSE.md](LICENSE.md)): you may use, study, change and share it, and anything you distribute that is built on it
must be under the same license, with its source code. It comes with no warranty.

Third-party parts keep their own licenses: the DocAligner model, PdfBox-Android and ZXing (Apache License 2.0), ONNX Runtime
(MIT), Google ML Kit (Google APIs Terms of Service) and the AndroidX / Jetpack libraries (Apache License 2.0).

## Credits

Page-corner detection uses the FastViT-SA24 heatmap model from [DocAligner](https://github.com/DocsaidLab/DocAligner) by DocsaidLab (Apache License 2.0; licence text in `app/src/main/assets/docaligner_LICENSE.txt`), run with [ONNX Runtime](https://onnxruntime.ai). The model is **not inside the APK** (that keeps the arm64 APK about 42 MB smaller): the first time it is needed on Wi-Fi, the app downloads the authors' published file (83 MB, from their Google Drive link) in the background and checks its SHA-256; on mobile data it waits until you tap **Download** in Me → Scan → Page detection model, which also shows progress. Until then pages are found with the line and paper-colour finders. Phones that had the earlier built-in copy keep using it. The fp16 copy previously shipped is kept in `models/` for reference. On SmartDoc 2015 frames it reaches a mean IoU of 0.99 versus 0.16 for the previous edge-only detector, which remains as the fallback. When the model finds no page in the stretched photo (tall phone photos, signs, pages touching the photo edge), it takes a second look at the photo on a square canvas with a 5 % margin; this fixed the two badly placed corners in the scanner app sample photos without changing the SmartDoc score.

Me → Scan → **Page detection model** chooses the corner finder: the built-in DocAligner model, *Edge detection only* (no model: lightest, less reliable on busy backgrounds), or your own `.onnx` file via *Import .onnx*. An imported model needs one image input (1 × 3 × H × W or 1 × H × W × 3, pixel values 0..1) and outputs either 4 corner heatmaps (1 × 4 × H × W, like DocAligner) or 8 corner coordinates (TL, TR, BR, BL; 0..1 or input pixels). It is checked on import, can be switched between BGR and RGB input, and deleted again.
