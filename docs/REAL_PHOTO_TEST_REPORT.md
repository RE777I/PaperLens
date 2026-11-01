# Real-photo test report

Test date: 17 Oct 2026. Device: Android emulator `PaperLens_Pixel7` (API 35, x86_64), debug build.
Input: the 15 photos in `rawScreenshot/testPhoto/`. All 15 were imported at once via Home → Import Files.

Before/after sheets (original photo | auto-crop | Magic Color page):

- [test-results/real_photos_1-8.png](test-results/real_photos_1-8.png)
- [test-results/real_photos_9-15.png](test-results/real_photos_9-15.png)

## 1. Auto crop ("Auto Adjust Borders" on import)

The first run used the old colour-based detector. It cut test15 down to the floor and missed test3 and test5, so I replaced it with an edge-and-line detector in `imaging/DocumentDetector.kt`.

| Photo | Result | Verdict |
|---|---|---|
| test3 memorial card | card only, frame removed | good |
| test4 "v2.1" box | box front | good |
| test5 Employee Benefits | card only | good |
| test6 tissue pack | left whole (no clear page) | good |
| test7 textbook | page only, desk removed | good |
| test8 QR stand | QR card only | good |
| test9 box on workbook | left whole (cluttered scene) | good (was a wrong crop before) |
| test10 floor sign | sign upright and readable, plus a strip of the curb | acceptable (was sideways before) |
| test11 menu board | menu only | good |
| test12 whiteboard | board only | good |
| test13 shop front | left whole | good |
| test14 projector | screen only | good |
| test15 poster | poster only | good |
| test1 crumpled paper | cropped to the printed text | acceptable: the paper isn't a clean rectangle |
| test2 photo album | crops to the photo inside the album, not the whole album | known issue: use Crop to widen |

test1 and test2 are small, low-resolution web images (about 500 px). The detector works at that size, but it has fewer edge pixels to work with.

## 2. Filters (Magic Color, which is also Whiteboard mode)

- **Documents:** very good on test3, 5, 7, 8, 11 and 12. The whiteboard turns clean white with dark handwriting.
- **Photo-like pages:** too strong on test2, 13, 14 and 15, which come out over-bright and over-saturated. Choose **Original** or **Lighten** for those pages.

## 3. OCR (Extract Text)

- **Latin:** reads English and Vietnamese well, for example "EMPLOYEE BENEFITS", the memorial card text, the menu's English names, and "BÁNH ĐẬU XANH … HƯƠNG NGUYÊN".
- **Chinese:** test10 reads 水池湿滑请勿戏水 exactly. The menu, textbook, poster and shop sign are mostly right.

## 4. Edit Text (on-page)

- **Selection:** tapping a line selects exactly that line, with handles and the Copy / Delete / Select All / Edit Text menu.
- **Replacing text:** works, and the result can be saved.

## 5. QR codes

Me → Code history → **image button** (new) reads both codes on the test8 photo: the WhatsApp link and the WeChat link. Each code is saved to history separately.

## Bugs found and fixed during this test

1. **Auto crop:** the detector was rewritten as described in section 1. Tilted documents now keep the right way up (test10 used to come out sideways). Small boxes that touch the photo edge are rejected (test9).
2. **OCR language change was ignored.** Stored text from the old language was reused. Changing the OCR language now clears it, so pages are read again.
3. **Chinese, Japanese, Korean and Devanagari OCR hung forever on first use.** The app waited for a "download finished" callback that Play services doesn't always send. It now also checks every 2 s, and stops after 3 min with a clear message if the model can't download.
4. **Edit Text on light text over a coloured band** (the poster headline): the patch and text colours were swapped. The colours now come from inside the line's box, so both light-on-dark and dark-on-light text keep their colours.
5. **Edit Text with longer replacement text** ran off the page. The text is now narrowed and, if needed, shrunk and centred to fit the original box.
6. **QR codes could only be scanned with the camera.** Codes can now be read from a saved photo. The "Open" button appears only when the result is a single link.

## Checks

- **Build:** `assembleDebug` and `lintDebug` succeed, with 0 lint errors. The 27 warnings are dependency-version notices and checks inside bundled libraries.
- **Crashes:** the crash log was empty after every run: two 15-photo imports, a 3-photo import, OCR in both languages, Edit Text and the QR import.
