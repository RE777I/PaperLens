# PaperLens

An Android document scanner modelled on a popular scanner app, formerly called SnapScanner (renamed 2026-11-01 to avoid Snap Inc.'s "SNAP" trademark). App ID and package: `com.paperlens.app`. The project folder, the signing key (`snapscanner-release.jks`, alias `snapscanner` in `local.properties`) and the WSL vault path keep the old name. It scans, filters, crops, runs OCR, translates and exports to PDF, Word, Excel and images. Everything runs on the phone. The feature list is in `README.md`.

Read only the files a task needs; the map below says where things are. Don't open these unless the task is about them: `rawInstallation/` (the reference scanner app's APK, about 100 MB), `imaging/FilterCurves.kt` (generated), `PaperLens-arm64.apk`, `app/build/`.

## Stack
- Kotlin, Jetpack Compose (Material3), single Activity (`MainActivity`), and navigation in `ui/Navigation.kt`.
- Room (`data/AppDatabase.kt`, schema v6; add a `MIGRATION_x_y` for every schema change), DataStore (`data/Settings.kt`), CameraX.
- ML Kit text recognition (Latin, Chinese and other scripts; `ml/TextRecognizer.kt`), ML Kit barcode, translation and language downloads.
- ONNX page-corner model (`imaging/CornerModel.kt`): not in the APK. `downloadBuiltIn` fetches DocsaidLab's fp32 file (83 MB, Google Drive link, SHA-256 checked) into `noBackupFilesDir`, on its own over unmetered networks or from the Settings dialog; older installs keep their extracted fp16 copy (`LEGACY_FILE`). The fp16 file lives in `models/` (not packaged).
- SDK: compileSdk/targetSdk 35, minSdk 24. APK splits: arm64-v8a, armeabi-v7a, x86_64.

## Source map (`app/src/main/java/com/paperlens/app/`)
| Area | Files |
|---|---|
| Data | `data/Entities.kt` (documents and pages; a Word document has `wordPath`/`wordCover`, `isWord`), `DocumentDao.kt`, `DocumentRepository.kt` (all file and DB operations: create, import, duplicate, Word load/save) |
| Imaging | `imaging/ImageFilter.kt` (filters, `Look` model; curves in the generated `FilterCurves.kt`), `DocumentDetector.kt` / `PageFinder.kt` / `CornerModel.kt` (page edges), `PageComposer.kt`, `BitmapUtils.kt` |
| OCR / translate | `ml/TextRecognizer.kt` (`Ocr.recognizeText`), `TextLayout.kt`, `TextSearch.kt` (`SearchableText`: normalised text + per-character boxes for Find Text), `TableOcr.kt`, `Translator.kt`, `LanguageDownloads.kt` |
| Export | `export/Exporter.kt` (PDF/JPG/long image), `XlsxWriter.kt`, `PdfImporter.kt`, `Sharing.kt` |
| Word | `word/WordModel.kt` (`WordDocument`, `WParagraph`/`WRun`/`WImage`, `WTable`, `BlockPath`), `DocxWriter.kt`, `DocxReader.kt`, `WordEditing.kt`, `WordThumbnail.kt` |
| Page → Word converter | `word/PageToWord.kt` (`convertPages`, `PageBuilder`: font sizes, bold, handwriting, tables, pictures, paragraphs, layout), `word/PageAnalysis.kt` (pixel analysis: ruled lines, grid/band tables, picture finder, word colour/weight) |
| UI | `ui/main` (bottom tabs), `ui/home`, `ui/files`, `ui/tools` (`ToolRunner.kt` runs every tool: To Word, import, share, merge…), `ui/capture` (camera, `CropReview.kt` + `CropSnap.kt` for the crop screen), `ui/document` (`FindText.kt`: Find Text overlay, a pager with highlights and zoom-to-match), `ui/page`, `ui/edit` (`EditPagesScreen`: the Edit pager and its Filter sheet), `ui/word` (`WordScreen`, `WordPage` renders the document, `WordEditor`), `ui/translate`, `ui/settings`, `ui/components` (`PanZoom.kt`: shared pinch/double-tap zoom) |

## Key behaviour to keep
- To Word converts the **unfiltered** `originalPath` and creates a new Word document in the library, which then opens. It does not share the file.
- In the converter, handwriting (low OCR confidence or light ink) becomes inline picture runs (`OcrWord.handwritten`). `splitMixed` splits an unsure OCR word into sure (print) and unsure (handwriting) runs by ML Kit symbol confidence, so printed words beside handwriting stay text. While editing, `EditableText` keeps inline pictures in the line through `PictureGaps` (a VisualTransformation leaving a picture-sized gap; the editor text itself has no pictures). Flat shading is not a picture (`isFlat` in `pictures()`). A band table needs at least 3 shaded rows.
- Corner detection (`CornerModel.detectPass`): first the photo stretched to the model input with a plain `createScaledBitmap` (what DocAligner was trained on), and only if that finds nothing a padded square look (5 % margin, built with the halving `shrink()`). `DocumentDetector.detectOutline` uses the paper finder when a padded result agrees with it, or when the model's quad fills the photo. The model choice and imported models live in `files/corner_models/models.json` (+ `<id>.onnx`); the UI is `CornerModelSetting()` in `ui/settings/SettingsScreen.kt`.
- Detection scoring: the `detect` probe logs each finder; `$S/tools/det_eval.py <log> $S/fa_out/cs_quads.json` scores it against the reference scanner app's crops of `rawScreenshot/Filter/original_uncropped_*.jpg` (current: mean IoU 0.888, 4 photos with a corner >3 % off).
- Find Text (Document → More) replaced the document screen's Extract Text. It searches stored `ocrText` first; boxes are not stored, so a page is OCR'd again with positions when it is shown with matches (`storeOcrText` saves text for unread pages without starting an edit). Highlights split each ML Kit element box by character width, because symbol boxes drift right by about half a character. Back reports the page on screen and `DocumentScreen` scrolls there.
- Zoom in the page viewer (`PageScreen`) and Find Text uses `PanZoom` + `Modifier.panZoom`. It zooms about the point between the fingers (or the double-tapped point), clamps panning to the page edges and recentres at 1×. It ignores the finger-lift event's `Offset.Unspecified` centroid, and it only consumes events that move, so a double tap still zooms out while zoomed. `WordScreen.WordPageView` keeps the pinched point under the fingers by adjusting its scroll position instead. adb can't pinch on the emulator (no root for `sendevent`, and console `event send` touches don't register), so test it with double taps.
- Long-press selection → Edit passes the selected page ids to the editor (`Routes.editPages(..., selected)`, `?pages=1,2`). The editor's Filter sheet has a scope: This page / Selected pages (n) / All pages (n). It defaults to Selected when more than one page was selected.
- OCR language defaults to `OcrScript.AUTO`; all OCR goes through `Ocr.process` (TextLayout too), which reads Latin plus remembered (`ocrScriptsUsed`), phone-locale and installed scripts, and keeps a non-Latin reading only with ≥5 % higher Σ confidence×chars and ≥5 own-script characters. Never wait for a model download in AUTO (it is requested in the background). The `ocrscore` probe mode logs Latin vs Chinese readings per page for tuning.
- `DocumentDetector.plausible` rejects model quads whose opposite sides differ by >35° (a corner jumped onto an inner box).
- PDF pages: `PdfImporter` keeps each imported page as a one-page PDF (`PageEntity.pdfPath`, DB v7) plus its text in `ocrText`. `PageEntity.pdfFile` is that PDF only while filter is Original and rotation 0; `bakeEdit`, `replacePageImage` and `recropPage` drop it, and merge/extract copy it. `PdfPages` reads text with positions and renders regions; `PdfZoomLayer` redraws the visible part when zoomed (page viewer and Find Text). Exported PDFs import such pages unchanged; other pages get the hidden OCR text of `PdfTextLayer` when Settings → Searchable PDF is on.
- `DocxWriter` writes OOXML with strict element order. Imported .docx files keep their original parts (`SourcePackage`).

## Build and test (Windows, Git Bash)
- Gradle needs JDK 17: `export JAVA_HOME=$USERPROFILE/tools/jdk17` before `./gradlew.bat assembleDebug -q` or `./gradlew.bat lintDebug -q`. The system Java is 8 and fails.
- Release APK for the user: copy `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` to `PaperLens-arm64.apk` in the project root.
- Emulator: `emulator-5554` (x86_64). If it misbehaves, start it with `-gpu swiftshader_indirect`. adb is at `$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe`. Set `MSYS_NO_PATHCONV=1`.
- Debug probe (debug builds only; `app/src/debug/.../FilterProbe.kt`, `WordProbe.kt`): put JPEGs in the app's `files/probe/word` (or `files/probe/in` for filters), keep the app in the foreground, then:
  `adb shell am broadcast -n com.paperlens.app/.debug.FilterProbe --es mode word --es script CHINESE` (modes: none = filters, `detect`, `word`; script `LATIN`/`CHINESE`/…). Output goes to `files/probe/wordout` (`.docx`, `_debug.jpg` overlay, `.txt` structure dump).
- Python: the project venv is broken. Use `C:/Users/Robby_VB/.pyenv/pyenv-win/versions/3.12.2/python.exe`.
- Test tools and data from earlier sessions (`$S` below): `C:/Users/Robby_VB/AppData/Local/Temp/claude/C--Users-Robby-VB-Documents-Program-Claude-snapScanner/c5732b32-8e92-4dcc-938b-88468f63f51d/scratchpad` — `tools/t.sh` (adb/ui helpers), `tools/wordprobe2.sh`, `dl/smartdoc/sample` (SmartDoc frames + ground truth), `dl/models/` (fp32 DocAligner), `fa_out/cs_quads.json`. It is a temp folder, so it may be gone.
- There are no unit tests. Check changes with the probe and screenshots on the emulator, then run lint (baseline: 0 errors, about 31 warnings).

## Reference material
- `rawScreenshot/`: reference scanner app screenshots and the targets the app copies. `Filter/` has the filter references, `cropFeature.jpg` the crop screen, `wordFeature/` the Word conversion before/after pairs (`pic*_ori.jpg` / `pic*_word.jpg`), `testPhoto/` raw photos for testing.
- `docs/REAL_PHOTO_TEST_REPORT.md`, `docs/test-results/`. Local only (ignored by git): `rawScreenshot/reference/` holds the feature analysis of the reference scanner app and the filter/auto-crop comparison images. Keep the reference app's name out of tracked files; call it "the scanner app".

## Knowledge vault (claude-obsidian)
- The `claude-obsidian` plugin is installed for this project (`.claude/settings.json`). Its vault is in WSL at `/home/rby/snapscanner-vault` (Windows: `\\wsl.localhost\Ubuntu\home\rby\snapscanner-vault`, open it in Obsidian from there). README, CLAUDE.md and the two `docs/` reports are archived in its `.raw/captured/`; they are not yet turned into wiki notes.
- Native Windows can only read the vault: the plugin refuses writes there, and a vault on `C:` through `/mnt/c` fails its permission check. Run writes (ingest, save, lint fixes) from Claude Code inside WSL in the vault folder, e.g. `/claude-obsidian:wiki-ingest`.
- Plugin hooks call `python3`; here that is a pyenv `.bat` shim that hooks cannot start, so on Windows they stay silent (harmless).

## Conventions
- UI text lives in `res/values/strings.xml` with Indonesian (`values-in`) and Simplified Chinese (`values-zh`) copies; read it with `S.str(R.string.x, args…)` (works outside composables too). Every new user-visible string needs all three files. Enum labels hold `R.string` ids and expose `label` via `S.str`. Avoid key names that are Java keywords or that clash with library strings (`selected`, `not_selected`). Edge spaces must be quoted in XML.
- Accessibility: icon-only buttons need a translated `contentDescription`; a switch beside a title goes on a `Modifier.toggleable(role = Role.Switch)` row with `Switch(onCheckedChange = null)`. `$S/../c31f68cb-…/scratchpad/i18n/unlabeled.py <uiautomator dump>` lists unnamed tappable nodes.
- Backups: cloud backups keep only `files/datastore` (settings). New files that should be backed up must be added to `res/xml/data_extraction_rules.xml` and `backup_rules.xml`.
- Find Text positions: `PageEntity.ocrLayout` (DB v8) stores word boxes stamped with the image file name; `SearchableText.fromLayout` ignores it once the image changes.
- `docs/IMPROVEMENT_PLAN.md` is the numbered list of planned improvements; the user picks items by number. When you
  finish one, tick it (`[x]`) with the date and a one-line note of what changed and how it was checked. Add new
  items at the end and never renumber.
- Match the surrounding code: KDoc on classes and non-obvious functions, short comments that explain why.
- Update `README.md` when a user-visible feature changes.
- Not a git repository, so there is nothing to commit.
