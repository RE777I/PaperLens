# PaperLens improvement plan

Pick items by number ("do #2 and #6"). When an item is finished it is ticked (`[x]`) with the date and a one-line
note of what changed and how it was checked. Numbers never change, so new items are added at the end.

Effort: **S** = an hour or two, **M** = about a day, **L** = several days.

## For people using the app

- [ ] **1. Ship the release build** (S). The APK used for testing is a debug build: 119 MB and slower. The release
  build (R8, resource shrinking, signing) is already set up and comes out around 35 MB for arm64.
  *Done when:* `PaperLens-arm64.apk` comes from `assembleRelease`, and import, OCR, translate and export are smoke-tested on it.
- [x] **2. Searchable exported PDFs** (M). *Done 2026-11-01:* invisible OCR text layer (`export/PdfTextLayer.kt`,
  glyph-less Identity-H font, works for Chinese too), switch in Settings → Share & Export. Checked: pypdf reads the
  text of an exported scan, and the hidden words sit on their printed words from 14 pt down to 4 pt.
  Original task: Exported PDFs are images only: you can't search, select or copy their text.
  Put the OCR text invisibly under each page image, as scanner apps do.
  *Done when:* an exported PDF finds words with a PDF reader's search, and the text lines up with the page.
- [x] **3. Sharp, text-keeping PDF import** (L). *Done 2026-11-01:* each imported page keeps a one-page PDF
  (`pdfPath`, DB v7) and its own text. Zoom redraws from the PDF (`PdfZoomLayer`), Find Text uses the PDF text, and
  export uses the original page. Checked on the emulator: an A1 poster page is crisp when zoomed (jagged as a picture),
  "warranty" found 18/18 with exact highlights without OCR, Chinese text stored exactly, export matches the original's text.
  Original task: Imported PDFs are turned into 300 dpi images (at most 3,508 px). Their
  real text is lost, and small print blurs past about 3× zoom. Keep the original PDF, redraw the visible part sharp when
  zoomed, and use its text layer for search and Find Text instead of OCR.
  *Done when:* a text PDF stays crisp at high zoom, and Find Text matches its text without running OCR.
- [x] **4. Language detection for To Word, Extract/Find Text and To Excel** (M). *Done 2026-11-01:* OCR language
  "Automatic" (new default) in `Ocr.process`: Latin plus remembered / phone / installed scripts, a non-Latin reading
  wins with ≥5 % more confident text and ≥5 characters of its script; scripts used are remembered
  (`ocrScriptsUsed`). Checked: To Word on 3 Chinese and 2 English pages gives the same text as the right fixed
  language for each. Original task: Today these read pages in the one OCR
  language chosen in Settings, so a Mandarin page converts badly when Settings is on Latin. Detect the script per page
  (try Latin and the languages this user has used before, keep the best reading) and remember languages used.
  *Done when:* a Chinese page and an English page convert correctly with Settings left on Latin.
- [x] **5. Better auto-crop on import** (M). *Done 2026-11-01:* the model's page shape is rejected when its opposite
  sides differ by more than 35° (real pages: at most 24.9° over 24,889 SmartDoc frames); pic2's corner had jumped onto
  the green panel (48.8°). Checked: pic2 imports as the whole page; detection score unchanged (mean IoU 0.888).
  Original task: On import the page detector can pick a box inside the page (pic2's green
  panel) as the page. Prefer the outer page when an inner shaded box is found.
  *Done when:* pic2 imports uncropped, with no regression on the detection score (`det_eval.py`, mean IoU 0.888).
- [x] **6. Instant Find Text** (S). *Done 2026-11-01:* word boxes stored per page (`ocrLayout`, DB v8, stamped with
  the image file name so edits invalidate it); OCR in Find Text and `ocrPage` saves them. Checked: second Find Text on
  a scanned page ran no OCR (0 model loads vs 10) with the same 17 highlighted matches. Original task: Word positions aren't stored, so a page is OCR'd again each time Find Text shows
  matches on it. Store the boxes with the page (Room migration 6→7).
  *Done when:* reopening Find Text on an already-read document highlights matches without OCR.
- [x] **7. Translatable app interface** (M). *Done 2026-11-01:* 848 UI literals in 36 files moved to 576
  `strings.xml` entries (read through `S.str`; enum labels too), with full Indonesian (`values-in`) and Simplified
  Chinese (`values-zh`) translations and per-app language support (`locales_config`). Checked: Home, Me, Tools and
  Files switch language live on the emulator. Not covered: text built outside `ui/` (error messages from
  exceptions, Word converter output). Original task: 148 pieces of on-screen text are written in the Kotlin code, so the app can't be
  shown in other languages. Move them to `strings.xml`, then add translations (e.g. Indonesian, Chinese).
  *Done when:* switching the phone language changes the app's own text.
- [x] **8. Screen-reader labels** (S). *Done 2026-11-01:* the accessibility tree of Home, Files, Tools, Me, document,
  page, camera and settings was checked for tappable items without a name. Fixed: search bar, document select boxes,
  App lock and settings switches (now one toggle row each); the English-only TalkBack names and progress messages are now
  translated. Checked: no unnamed controls left except the empty search text field inside its named box. Most of the
  34 icons without a description are decorative (next to their own text) and stay so. Original task: 34 icons have no label, so TalkBack says nothing for them.
  *Done when:* every icon button has a label and TalkBack reads the main screens.
- [x] **9. Private backups** (S). *Done 2026-11-01:* cloud backups include only `files/datastore` (settings);
  device-to-device transfer keeps everything except debug files; Android ≤11 rules keep only settings. Checked: a backup
  with the local transport held only `settings.preferences_pb` (~3.5 KB of 491 MB of scans). Restoring was not run (it
  would wipe the emulator's test library). Original task: `allowBackup="true"` lets phone backups copy every scan, even with App lock on. Add
  backup rules that leave out documents (or make it a setting).
  *Done when:* the backup rules leave out `files/` documents and settings still restore.
- [x] **10. Word conversion: handwriting edges** (M). *Done 2026-11-01:* unsure OCR words are split by per-character
  confidence (print 0.6–0.9, handwriting 0.03–0.45), so only the handwriting becomes a picture (`splitMixed`); editing
  keeps inline pictures in their line (`PictureGaps`). Checked: pic2 line (6) is now text "(6)我" + handwriting picture
  + text "(after… mother)…", no other paragraph of pic1–3 changed; editing shows the picture in place and typing works.
  Still pictures: ")再" before an answer (one sure character) and the green "V₁再V₂". Original task: Where OCR is unsure, printed words next to handwriting get cut
  into the handwriting picture (e.g. "(after" beside "给妈妈打了电话"). While editing, a paragraph's handwriting pictures move
  above its text.
  *Done when:* pic2's handwriting crops hold only handwriting, and editing keeps pictures in the line.
- [x] **11. Smaller download: model on demand** (M). *Done 2026-11-01:* the model left the APK (arm64 debug APK
  119.7 → 77.5 MB); the authors' fp32 file (83 MB) downloads on Wi-Fi by itself or from the Settings dialog (with
  progress), SHA-256 checked; older installs keep their copy. Checked on the emulator: upgrade keeps the old copy
  (detection score 0.888), a fresh download completes and detection scores 0.888 again. Original task: The 42 MB page-corner model ships inside the APK. Download it on
  first use (with edge detection meanwhile), or offer it as an optional download.
  *Done when:* the APK is about 40 MB smaller and detection works after the download.

## For development

- [x] **12. Version control** (S). *Done 2026-11-01:* git repository with `.gitignore` (build output, APKs,
  `local.properties`, keystores, `rawInstallation/`, `rawScreenshot/`, `models/`) and `.gitattributes`; releases via
  `scripts/release.ps1` (signed APKs on GitHub Releases). Checked: no secrets or binaries in the 132 tracked files.
  Original task: The project isn't in git, so there's no history and no easy undo of a large change.
  Set up git with `.gitignore` for build output, APKs and keystores, and make a first commit.
  *Done when:* `git log` shows the baseline commit and build output isn't tracked.
- [ ] **13. Move test tools and data into the project** (S). The detection scorer, scanner app reference crops, SmartDoc
  frames and probe scripts live in a temporary folder that Windows can delete. Move them to `tools/` and `testdata/`.
  *Done when:* the scripts run from the project folder, and CLAUDE.md points there.
- [ ] **14. Automated tests** (M). There are none. Start with:
  - Word: write → read back gives the same document.
  - Converter: the wordFeature pages keep their tables, pictures and handwriting.
  - Detection: the scanner app crop score as a test that fails if it drops.
  *Done when:* `gradlew test` (or a documented script) runs them and passes.
- [x] **15. Split the largest files** (M). *Done 2026-11-02:* `DocxReader` → `DocxReader`/`DocxXml`/`DocxBody`,
  `PageAnalysis` → + `PageShapes`/`PagePictures`/`PageInk`, `PageToWord` → + `PageTables`, `CaptureScreen` → +
  `CaptureParts`/`CameraControls`, `DocumentScreen` → + `DocumentParts`, `DocumentRepository` → + `RepositoryLibrary`;
  largest file now 634 lines. Checked: Word probe (5 pages) and detect probe (17 photos) identical to before, camera,
  document screen, sheets, selection and .docx edit/save/reopen tried on the emulator.
  Original task: `DocxReader.kt` (1,193 lines), `PageAnalysis.kt` (983),
  `DocumentScreen.kt` (862) and `CaptureScreen.kt` (809) are hard to change safely. Split them by responsibility.
  *Done when:* no file is over about 600 lines and behaviour is unchanged (probe and screenshots match).
- [x] **16. Don't swallow errors** (S). *Done 2026-11-02:* 31 silent calls now use `runLogged(tag, what)`
  (`Failures.kt`, same fallbacks); the other 14 already showed a message or returned the error. Automatic
  Save to Gallery after a scan now says when pages didn't save (new string in all three languages).
  Original task: 34 `runCatching` calls ignore failures silently. Log each one, and show a message
  where the user is affected.
  *Done when:* every `runCatching` logs or reports its failure.
- [x] **17. Update libraries** (S). *Done 2026-11-02:* all libraries current (Compose BOM 2026.09, Room 2.8,
  CameraX 1.6, Navigation 2.10, ONNX Runtime 1.31.1 — now 16 KB-page aligned), which needed AGP 9.5, Gradle 9.8,
  Kotlin 2.4, KSP 2.3 and compileSdk 37 (targetSdk stays 35). Security pass: all 221 runtime libraries clean on
  OSV after pinning BouncyCastle 1.86 (was 1.72) and Guava 33.7.2; only official publishers; libraries add no
  data permissions. Checked: lint 0 errors / no GradleDependency, probes identical (detection to 0.001 px), smoke
  test incl. a password PDF verified with pypdf.
  Original task: 19 libraries have newer versions (Compose BOM is 2024.09).
  *Done when:* lint shows no `GradleDependency` warnings, and the app builds and passes a smoke test.
- [x] **18. Excel files and a better To Excel** (L). *Done 2026-11-02:* .xlsx files import (Import Files,
  Open with, Share) into the library (DB v9 `excelPath`, X badge, sheet cover) and open in a sheet viewer
  (`ui/excel`: tabs, merges, widths, formats, frozen panes, fling, pinch zoom, cell editing with Save/Discard that
  patches only the edited cells). To Excel (`excel/SheetBuilder.kt`) now uses ruled grids (shared with To Word),
  shaded-row and borderless tables built column first, divider lines, header rows, multi-line cells and typed
  numbers, and saves a new Excel document. Checked with the new `excel` probe mode on 10 table photos (6 from
  Wikimedia Commons, 4 of the user's): mean cell accuracy 34 % → 89 %, rows fully right 12 % → 82 %, expected numbers
  stored as numbers 2 % → 85 % (35 of 41); an openpyxl workbook imported from Downloads displayed, edited, saved and re-read intact;
  Word probe unchanged; lint 0 errors.
  Original task: read .xlsx files like Word documents, and make To Excel much better at real tables.
  *Done when:* an imported .xlsx displays correctly and To Excel scores clearly better on a table test set.
- [x] **19. Tools tab: smarter picker, real Compress, Page Numbers, Create Code** (M). *Done 2026-11-16:* the banner
  reads "Toolkit". The document picker lists only documents the tool can use, has a search box from 7 documents and
  Scan new / Import file shortcuts that continue into the tool (`ToolRunner.scanFor`/`importFor`/`performAfter`).
  Compress shows estimated sizes for the three qualities, shares or saves to the device, and no longer leaves
  imported PDF pages untouched (`Exporter.pdf(shrinkPdfPages)`, `estimatePdfBytes`). New tools: Page Numbers
  (`PageComposer.pageNumber`) and Create Code (`imaging/QrCodes.kt`, ZXing core 3.5.3, OSV clean). Fixed: the
  camera's To Word / To Excel / Timestamp modes saved the scan but skipped the tool (a busy task started inside
  another was dropped). Checked on the emulator: each dialog, Compress via Import file and via Scan new, numbers
  "1 / 3" on three pages, a created code decoded with OpenCV, timestamp stamped after a camera scan; lint 0 errors.
- [x] **20. Readability: shallower loops** (M). *Done 2026-11-17:* license switched to GPL-3.0-or-later. Loops nested 4+ deep went from 6 to 0 and 3+ deep from 23 to 6 (script `nesting.py`): `detectByEdges`, `blur`, `hough`, `hullQuad`, `fitCubic`, `boxBlur`/`maxFilter`, `mosaic`, the picture finder (`PagePictures.kt`), ruled-line tracking and grid cells (`PageAnalysis.kt`, shared `DisjointSets`) were split into small named functions. Checked: Word probe (5 pages), detect probe (17 photos) and To Excel probe (10 photos, 60 output files) are byte-identical to the build before; lint 0 errors. *Left:* the 3-deep `DocxXml.parse`, `SheetGrid.area`, `SheetBuilder.emitGrid`/`splitAtRules`, and the 100–500-line Compose screens (`DocumentScreen`, `CameraContent`, `FilesTab`…).

## Already done (for reference)

- [x] Page-corner model choice and `.onnx` import; second, padded look for tall photos (2026-10-28; mean IoU 0.881 → 0.888, photos 11 and 13 fixed).
- [x] Find Text (Ctrl+F-style search with 1/n counter and arrows) replaced Extract Text (2026-10-28).
- [x] Zoom loads the full-resolution page, so scans stay sharp when zoomed (2026-10-28).
- [x] Word conversion like the wordFeature examples: inline handwriting, flowing paragraphs, no shading pictures (2026-10-26).
