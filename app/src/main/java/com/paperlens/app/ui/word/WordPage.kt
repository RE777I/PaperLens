package com.paperlens.app.ui.word

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paperlens.app.word.BlockPath
import com.paperlens.app.word.LineRule
import com.paperlens.app.word.RunStyle
import com.paperlens.app.word.TabKind
import com.paperlens.app.word.VAlign
import com.paperlens.app.word.VMerge
import com.paperlens.app.word.WAlign
import com.paperlens.app.word.WBlock
import com.paperlens.app.word.WBorder
import com.paperlens.app.word.WImage
import com.paperlens.app.word.WMedia
import com.paperlens.app.word.WPageBreak
import com.paperlens.app.word.WParagraph
import com.paperlens.app.word.WTable
import com.paperlens.app.word.WordDocument
import com.paperlens.app.word.WordEditing
import com.paperlens.app.word.gridStarts
import com.paperlens.app.word.rowSpan
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Decoded pictures of a document, by media key (decoded once, at most ~1600 px). */
internal class PictureCache(private val media: () -> Map<String, WMedia>) {
    private val cache = HashMap<String, ImageBitmap?>()

    fun get(key: String): ImageBitmap? = cache.getOrPut(key) {
        val m = media()[key] ?: return@getOrPut null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(m.bytes, 0, m.bytes.size, opts)
        var sample = 1
        while (opts.outWidth / (sample * 2) >= 1600 || opts.outHeight / (sample * 2) >= 1600) sample *= 2
        BitmapFactory.decodeByteArray(m.bytes, 0, m.bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }
}

/** What the page drawing needs: scale, pictures, and (when editing) the editor. */
internal class PageContext(
    val pxPerTwip: Float,
    val density: Density,
    val pictures: PictureCache,
    val editor: WordEditor?,
    val labels: Map<BlockPath, String>,
) {
    fun dp(twips: Int): Dp = with(density) { (twips * pxPerTwip).toDp() }
    fun dpEmu(emu: Long): Dp = with(density) { (emu / 635f * pxPerTwip).toDp() }

    /** Points to sp so the text is exactly its size on the scaled page, whatever the system font scale. */
    fun sp(pt: Float): TextUnit = with(density) { (pt * 20 * pxPerTwip).toSp() }
}

/** The document as a white sheet: margins, blocks top to bottom, floating pictures over them. */
@Composable
internal fun WordSheet(doc: WordDocument, ctx: PageContext, modifier: Modifier = Modifier) {
    val page = doc.page
    // Where each top-level paragraph is (for pictures that float relative to their paragraph or page).
    val anchors = remember(doc) { HashMap<Int, Float>() }
    var sheetTop by remember { mutableStateOf(0f) }
    var layoutTick by remember { mutableStateOf(0) }
    Box(
        modifier
            .background(Color.White)
            .onGloballyPositioned { sheetTop = it.positionInRoot().y },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = ctx.dp(page.marginLeft), end = ctx.dp(page.marginRight), top = ctx.dp(page.marginTop), bottom = ctx.dp(page.marginBottom)),
        ) {
            doc.blocks.forEachIndexed { i, b ->
                Box(Modifier.onGloballyPositioned {
                    val y = it.positionInRoot().y - sheetTop
                    if (anchors[i] != y) { anchors[i] = y; layoutTick++ }
                }) {
                    BlockView(b, BlockPath.body(i), ctx, ctx.dp(page.textWidth))
                }
            }
        }
        // Floating pictures: page-relative ones from the top of their page, others from their paragraph.
        @Suppress("UNUSED_EXPRESSION") layoutTick
        var pageTop = 0f
        val pageHeightPx = page.height * ctx.pxPerTwip
        doc.blocks.forEachIndexed { i, b ->
            if (b == WPageBreak) pageTop = anchors[i] ?: (pageTop + pageHeightPx)
            if (b !is WParagraph) return@forEachIndexed
            for (img in b.images) {
                val f = img.float ?: continue
                val x = f.x / 635f * ctx.pxPerTwip + if (f.relativeH == "page") 0f else page.marginLeft * ctx.pxPerTwip
                val y = f.y / 635f * ctx.pxPerTwip + when (f.relativeV) {
                    "page" -> pageTop
                    "margin", "topMargin" -> pageTop + page.marginTop * ctx.pxPerTwip
                    else -> anchors[i] ?: pageTop
                }
                PictureView(img, ctx, Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) })
            }
        }
    }
}

@Composable
internal fun BlockView(b: WBlock, path: BlockPath, ctx: PageContext, width: Dp) {
    when (b) {
        is WParagraph -> ParagraphView(b, path, ctx)
        is WTable -> TableView(b, path, ctx)
        WPageBreak -> Box(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp)
                .height(1.dp)
                .background(Color(0xFFBDBDBD)),
        )
    }
}

@Composable
internal fun PictureView(img: WImage, ctx: PageContext, modifier: Modifier = Modifier) {
    val bitmap = ctx.pictures.get(img.mediaKey)
    val m = modifier.size(ctx.dpEmu(img.widthEmu), ctx.dpEmu(img.heightEmu))
    if (bitmap != null) Image(bitmap, null, m, contentScale = ContentScale.FillBounds) else Box(m.background(Color(0xFFEEEEEE)))
}

// ---- paragraphs --------------------------------------------------------------------------------

private fun fontFamily(name: String?): FontFamily? {
    val n = name?.lowercase() ?: return null
    return when {
        listOf("times", "serif", "georgia", "cambria", "garamond", "song", "mincho", "batang", "book").any { it in n } && "sans" !in n -> FontFamily.Serif
        listOf("courier", "mono", "consolas").any { it in n } -> FontFamily.Monospace
        else -> null
    }
}

internal fun spanStyle(s: RunStyle, ctx: PageContext) = SpanStyle(
    fontSize = ctx.sp(s.size),
    fontWeight = if (s.bold) FontWeight.Bold else FontWeight.Normal,
    fontStyle = if (s.italic) FontStyle.Italic else FontStyle.Normal,
    textDecoration = when {
        s.underline && s.strike -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
        s.underline -> TextDecoration.Underline
        s.strike -> TextDecoration.LineThrough
        else -> null
    },
    color = s.color?.let { Color(0xFF000000.toInt() or it) } ?: Color.Black,
    background = s.highlight?.let { Color(0xFF000000.toInt() or it) } ?: Color.Unspecified,
    fontFamily = fontFamily(s.font),
)

/** The paragraph's text (pictures left out) with its formatting. */
internal fun annotated(p: WParagraph, ctx: PageContext): AnnotatedString = buildAnnotatedString {
    for (r in p.runs) {
        if (r.image != null || r.text.isEmpty()) continue
        withStyle(spanStyle(r.style, ctx)) { append(r.text) }
    }
}

/** The paragraph with its inline pictures in their places in the text (one placeholder character each). */
private fun annotatedWithPictures(p: WParagraph, ctx: PageContext): Pair<AnnotatedString, Map<String, InlineTextContent>> {
    val content = mutableMapOf<String, InlineTextContent>()
    val text = buildAnnotatedString {
        p.runs.forEachIndexed { i, r ->
            val img = r.image
            if (img != null) {
                if (img.float != null) return@forEachIndexed
                val id = "pic$i"
                appendInlineContent(id, " ")
                val (w, h) = with(ctx.density) { ctx.dpEmu(img.widthEmu).toSp() to ctx.dpEmu(img.heightEmu).toSp() }
                content[id] = InlineTextContent(Placeholder(w, h, PlaceholderVerticalAlign.TextCenter)) { PictureView(img, ctx) }
            } else if (r.text.isNotEmpty()) {
                withStyle(spanStyle(r.style, ctx)) { append(r.text) }
            }
        }
    }
    return text to content
}

private fun paragraphTextStyle(p: WParagraph, ctx: PageContext): TextStyle {
    val size = p.runs.filter { it.image == null }.maxOfOrNull { it.style.size } ?: p.firstStyle.size
    val lineHeight = when (p.lineRule) {
        LineRule.EXACT -> p.line?.let { ctx.sp(it / 20f) }
        LineRule.AT_LEAST -> p.line?.let { ctx.sp(max(it / 20f, size * 1.15f)) }
        LineRule.AUTO -> p.line?.takeIf { it != 240 }?.let { ctx.sp(size * 1.15f * it / 240f) }
    } ?: ctx.sp(size * 1.15f)
    return TextStyle(
        color = Color.Black,
        fontSize = ctx.sp(size),
        lineHeight = lineHeight,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
        textAlign = when (p.align) {
            WAlign.LEFT -> TextAlign.Start
            WAlign.CENTER -> TextAlign.Center
            WAlign.RIGHT -> TextAlign.End
            WAlign.JUSTIFY -> TextAlign.Justify
        },
    )
}

@Composable
internal fun ParagraphView(p: WParagraph, path: BlockPath, ctx: PageContext) {
    val editor = ctx.editor
    val focused = editor != null && editor.editing && editor.focus == path
    val hanging = if (p.indentFirst < 0) -p.indentFirst else 0
    val label = ctx.labels[path]
    val startPad = (p.indentLeft - hanging).coerceAtLeast(0)
    val border = p.borderBottom
    var box = Modifier
        .fillMaxWidth()
        .padding(top = ctx.dp(p.spaceBefore), bottom = ctx.dp(p.spaceAfter))
    if (p.shading != null) box = box.background(Color(0xFF000000.toInt() or p.shading))
    if (border != null) {
        val strokePx = with(ctx.density) { max(1f, border.size / 8f * 20 * ctx.pxPerTwip) }
        box = box.drawBehind {
            drawLine(Color(0xFF000000.toInt() or (border.color ?: 0)), Offset(ctx.dp(startPad).toPx(), size.height), Offset(size.width - ctx.dp(p.indentRight).toPx(), size.height), strokePx)
        }
    }
    if (focused) {
        box = box.border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
    }
    Box(box.padding(start = ctx.dp(startPad), end = ctx.dp(p.indentRight.coerceAtLeast(0)))) {
        Column(Modifier.fillMaxWidth()) {
            // Inline pictures: inside the text (a handwritten word in its sentence), also while it is being
            // edited (see EditableText); on a line of their own only when the paragraph has no text.
            val inline = p.images.filter { it.float == null }
            val text = annotated(p, ctx)
            val inText = inline.isNotEmpty() && text.isNotEmpty()
            if (inline.isNotEmpty() && !inText) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .pointerInput(path, editor?.editing) { detectTapGestures { editor?.takeIf { it.editing }?.focusParagraph(path, 0) } },
                    horizontalArrangement = when (p.align) {
                        WAlign.CENTER -> androidx.compose.foundation.layout.Arrangement.Center
                        WAlign.RIGHT -> androidx.compose.foundation.layout.Arrangement.End
                        else -> androidx.compose.foundation.layout.Arrangement.Start
                    },
                ) {
                    inline.forEach { PictureView(it, ctx) }
                }
            }
            if (text.isEmpty() && inline.isNotEmpty() && !focused) return@Column
            Row(Modifier.fillMaxWidth()) {
                if (label != null) {
                    Box(Modifier.width(ctx.dp(max(hanging, 360)))) {
                        Text(label, style = paragraphTextStyle(p, ctx).copy(textAlign = TextAlign.Start), fontSize = ctx.sp(p.firstStyle.size))
                    }
                }
                val firstIndent = if (label == null && p.indentFirst > 0) p.indentFirst else 0
                Box(Modifier.padding(start = ctx.dp(firstIndent).coerceAtLeast(0.dp))) {
                    if (focused) {
                        EditableText(p, path, ctx, editor!!)
                    } else {
                        StaticText(p, text, path, ctx, inText)
                    }
                }
            }
        }
    }
}

@Composable
private fun StaticText(p: WParagraph, text: AnnotatedString, path: BlockPath, ctx: PageContext, withPictures: Boolean) {
    val editor = ctx.editor
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val style = paragraphTextStyle(p, ctx)
    val (shown, pictures) = when {
        withPictures -> annotatedWithPictures(p, ctx)
        text.isEmpty() -> AnnotatedString(" ", spanStyle(p.firstStyle, ctx)) to emptyMap()
        else -> text to emptyMap()
    }
    val tapModifier = if (editor != null && editor.editing) {
        Modifier.pointerInput(path, shown) {
            detectTapGestures { pos ->
                var offset = layout?.getOffsetForPosition(pos) ?: 0
                // Picture placeholders are not in the editor's text.
                if (pictures.isNotEmpty()) offset -= shown.getStringAnnotations(INLINE_CONTENT_TAG, 0, offset).size
                editor.focusParagraph(path, offset.coerceAtLeast(0))
            }
        }
    } else Modifier
    if (p.tabs.isNotEmpty() && text.contains('\t') && !text.contains('\n')) {
        TabbedText(shown, p, ctx, style, tapModifier, pictures)
    } else {
        Text(
            shown.replaceTabs(),
            style = style,
            modifier = Modifier.fillMaxWidth().then(tapModifier),
            inlineContent = pictures,
            onTextLayout = { layout = it },
        )
    }
}

/** The annotation tag `appendInlineContent` marks its placeholders with. */
private const val INLINE_CONTENT_TAG = "androidx.compose.foundation.text.inlineContent"

/** Tabs without tab stops: a few spaces' width. */
private fun AnnotatedString.replaceTabs(): AnnotatedString =
    if (!contains('\t')) this else buildAnnotatedString {
        append(this@replaceTabs)
    }.let { s -> AnnotatedString(s.text.replace('\t', ' '), s.spanStyles, s.paragraphStyles) }

/** One line with tab stops: each part after a tab starts (or, for right tabs, ends) at its stop. */
@Composable
private fun TabbedText(
    text: AnnotatedString,
    p: WParagraph,
    ctx: PageContext,
    style: TextStyle,
    modifier: Modifier,
    pictures: Map<String, InlineTextContent> = emptyMap(),
) {
    val parts = ArrayList<AnnotatedString>()
    var start = 0
    for (i in text.indices) if (text[i] == '\t') { parts += text.subSequence(start, i); start = i + 1 }
    parts += text.subSequence(start, text.length)
    val stops = p.tabs.sortedBy { it.position }
    val indentPx = (p.indentLeft - (if (p.indentFirst < 0) -p.indentFirst else 0)).coerceAtLeast(0) * ctx.pxPerTwip
    Layout(
        content = { parts.forEach { Text(it, style = style.copy(textAlign = TextAlign.Start), softWrap = false, inlineContent = pictures) } },
        modifier = modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(Constraints()) }
        val positions = IntArray(placeables.size)
        var x = 0f
        var stopIndex = 0
        placeables.forEachIndexed { i, pl ->
            if (i > 0) {
                // Next stop to the right of where the text got to (positions are from the margin).
                while (stopIndex < stops.size && stops[stopIndex].position * ctx.pxPerTwip - indentPx <= x + 1) stopIndex++
                val stop = stops.getOrNull(stopIndex)
                if (stop != null) {
                    val sx = stop.position * ctx.pxPerTwip - indentPx
                    x = when (stop.kind) {
                        TabKind.RIGHT -> max(x, sx - pl.width)
                        TabKind.CENTER -> max(x, sx - pl.width / 2f)
                        else -> sx
                    }
                    stopIndex++
                } else {
                    val step = 720 * ctx.pxPerTwip
                    x = ((x / step).toInt() + 1) * step
                }
            }
            positions[i] = x.roundToInt()
            x += pl.width
        }
        val width = constraints.maxWidth.takeIf { it != Constraints.Infinity } ?: x.roundToInt()
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(width, height) { placeables.forEachIndexed { i, pl -> pl.place(positions[i], 0) } }
    }
}

@Composable
private fun EditableText(p: WParagraph, path: BlockPath, ctx: PageContext, editor: WordEditor) {
    val focus = remember { FocusRequester() }
    val shown = editor.value.copy(annotatedString = annotated(p, ctx))
    // The editor holds the text only; inline pictures are shown in their places by leaving a gap of their
    // size in the displayed text and drawing them into it, so editing doesn't move them out of the line.
    val slots = remember(p) { pictureSlots(p) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val transform = remember(slots, ctx) { PictureGaps(slots, ctx) }
    BasicTextField(
        value = shown,
        onValueChange = { editor.onValueChange(it) },
        textStyle = paragraphTextStyle(p, ctx),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        visualTransformation = if (slots.isEmpty()) VisualTransformation.None else transform,
        onTextLayout = { layout = it },
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focus)
            .drawWithContent {
                drawContent()
                val l = layout ?: return@drawWithContent
                slots.forEachIndexed { i, (at, img) ->
                    val pos = transform.gapIndex(i, at, shown.text.length)
                    if (pos >= l.layoutInput.text.length) return@forEachIndexed
                    val bitmap = ctx.pictures.get(img.mediaKey) ?: return@forEachIndexed
                    val line = l.getLineForOffset(pos)
                    val x0 = l.getHorizontalPosition(pos, usePrimaryDirection = true)
                    val x1 = l.getHorizontalPosition(pos + 1, usePrimaryDirection = true)
                    // Same size as the gap and as the picture outside editing: the page's size in text units.
                    val w = with(ctx.density) { ctx.dpEmu(img.widthEmu).toSp() }.toPx()
                    val h = with(ctx.density) { ctx.dpEmu(img.heightEmu).toSp() }.toPx()
                    val top = (l.getLineTop(line) + l.getLineBottom(line) - h) / 2
                    drawImage(
                        bitmap,
                        dstOffset = IntOffset(min(x0, x1).roundToInt(), top.roundToInt()),
                        dstSize = IntSize(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1)),
                    )
                }
            },
    )
    LaunchedEffect(path) { runCatching { focus.requestFocus() } }
}

/** Where each inline picture sits in the paragraph's text (characters of text before it) and the picture. */
private fun pictureSlots(p: WParagraph): List<Pair<Int, WImage>> {
    val out = ArrayList<Pair<Int, WImage>>()
    var at = 0
    for (r in p.runs) {
        val img = r.image
        if (img != null) {
            if (img.float == null) out += at to img
        } else {
            at += r.text.length
        }
    }
    return out
}

/**
 * Shows the editor's text with a picture-sized gap at each slot: a space set to the picture's height,
 * widened with letter spacing to its width. The editor's text and cursor positions stay as they are.
 */
private class PictureGaps(private val slots: List<Pair<Int, WImage>>, private val ctx: PageContext) : VisualTransformation {
    /** The gap of slot [i] (at text position [at]) in the displayed text. */
    fun gapIndex(i: Int, at: Int, textLength: Int) = at.coerceIn(0, textLength) + i

    override fun filter(text: AnnotatedString): TransformedText {
        val stops = slots.map { it.first.coerceIn(0, text.length) }
        val shown = buildAnnotatedString {
            var pos = 0
            slots.forEachIndexed { i, (_, img) ->
                append(text.subSequence(pos, stops[i]))
                pos = stops[i]
                val w = with(ctx.density) { ctx.dpEmu(img.widthEmu).toSp() }
                val h = with(ctx.density) { ctx.dpEmu(img.heightEmu).toSp() }
                // A space is about a quarter of its font size wide; spacing makes up the rest of the picture's width.
                withStyle(SpanStyle(fontSize = h, letterSpacing = (w.value - h.value * 0.25f).coerceAtLeast(0f).sp)) { append(' ') }
            }
            append(text.subSequence(pos, text.length))
        }
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = offset + stops.count { it < offset }
            override fun transformedToOriginal(offset: Int): Int {
                var gapsBefore = 0
                stops.forEachIndexed { i, s -> if (s + i < offset) gapsBefore++ }
                return (offset - gapsBefore).coerceIn(0, text.length)
            }
        }
        return TransformedText(shown, mapping)
    }
}

// ---- tables ------------------------------------------------------------------------------------

@Composable
internal fun TableView(t: WTable, path: BlockPath, ctx: PageContext) {
    val cols = t.grid
    val total = cols.sum().coerceAtLeast(1)
    val leftPad = when (t.align) {
        WAlign.CENTER -> null
        WAlign.RIGHT -> null
        else -> ctx.dp(t.indent.coerceAtLeast(0))
    }
    // The visible cells (vertical-merge continuations are drawn by the cell that starts the merge).
    data class Slot(val row: Int, val cell: Int, val col: Int, val span: Int, val rows: Int)
    val slots = ArrayList<Slot>()
    t.rows.forEachIndexed { r, row ->
        val starts = row.gridStarts()
        row.cells.forEachIndexed { c, cell ->
            if (cell.vMerge != VMerge.CONTINUE) slots += Slot(r, c, starts[c], cell.span.coerceAtLeast(1), t.rowSpan(r, c))
        }
    }
    val lineColor = Color(0xFF404040)
    Box(Modifier.fillMaxWidth(), contentAlignment = if (t.align == WAlign.CENTER) Alignment.TopCenter else if (t.align == WAlign.RIGHT) Alignment.TopEnd else Alignment.TopStart) {
        Layout(
            modifier = Modifier.padding(start = leftPad ?: 0.dp),
            content = {
                for (s in slots) {
                    val cell = t.rows[s.row].cells[s.cell]
                    val cb = cell.borders
                    fun side(cellBorder: WBorder?, off: Boolean, edge: Boolean, outer: WBorder?, inner: WBorder?): WBorder? =
                        if (off) null else cellBorder ?: if (edge) outer else inner
                    val top = side(cb?.top, cb?.noTop == true, s.row == 0, t.borders.top, t.borders.insideH)
                    val bottom = side(cb?.bottom, cb?.noBottom == true, s.row + s.rows >= t.rows.size, t.borders.bottom, t.borders.insideH)
                    val left = side(cb?.left, cb?.noLeft == true, s.col == 0, t.borders.left, t.borders.insideV)
                    val right = side(cb?.right, cb?.noRight == true, s.col + s.span >= cols.size, t.borders.right, t.borders.insideV)
                    var m: Modifier = Modifier
                    if (cell.shading != null) m = m.background(Color(0xFF000000.toInt() or cell.shading))
                    m = m.drawBehind {
                        fun w(b: WBorder) = max(1f, b.size / 8f * 20 * ctx.pxPerTwip)
                        fun c(b: WBorder) = b.color?.let { Color(0xFF000000.toInt() or it) } ?: lineColor
                        top?.let { drawLine(c(it), Offset(0f, 0f), Offset(size.width, 0f), w(it)) }
                        bottom?.let { drawLine(c(it), Offset(0f, size.height), Offset(size.width, size.height), w(it)) }
                        left?.let { drawLine(c(it), Offset(0f, 0f), Offset(0f, size.height), w(it)) }
                        right?.let { drawLine(c(it), Offset(size.width, 0f), Offset(size.width, size.height), w(it)) }
                    }
                    Box(
                        m.padding(horizontal = ctx.dp(t.cellMarginH), vertical = 1.dp),
                        contentAlignment = when (cell.vAlign) {
                            VAlign.TOP -> Alignment.TopStart
                            VAlign.CENTER -> Alignment.CenterStart
                            VAlign.BOTTOM -> Alignment.BottomStart
                        },
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            val cellWidth = ctx.dp((s.col until s.col + s.span).sumOf { cols.getOrElse(it) { 0 } } - 2 * t.cellMarginH)
                            cell.blocks.forEachIndexed { k, b ->
                                BlockView(b, BlockPath(path.steps + listOf(s.row, s.cell, k)), ctx, cellWidth)
                            }
                        }
                    }
                }
            },
        ) { measurables, constraints ->
            val scaleX = ctx.pxPerTwip
            val colX = IntArray(cols.size + 1)
            for (i in cols.indices) colX[i + 1] = colX[i] + (cols[i] * scaleX).roundToInt()
            val rowH = IntArray(t.rows.size) { r -> ((t.rows[r].height ?: 0) * ctx.pxPerTwip).roundToInt() }
            val exact = BooleanArray(t.rows.size) { r -> t.rows[r].exactHeight && t.rows[r].height != null }
            val widths = slots.map { s -> colX[(s.col + s.span).coerceAtMost(cols.size)] - colX[s.col] }
            val natural = measurables.mapIndexed { i, m -> m.maxIntrinsicHeight(widths[i].coerceAtLeast(1)) }
            // Single-row cells set their row's height; merged cells add to their last row if needed.
            slots.forEachIndexed { i, s -> if (s.rows == 1 && !exact[s.row]) rowH[s.row] = max(rowH[s.row], natural[i]) }
            slots.forEachIndexed { i, s ->
                if (s.rows > 1) {
                    val have = (s.row until s.row + s.rows).sumOf { rowH[it] }
                    val last = s.row + s.rows - 1
                    if (natural[i] > have && !exact[last]) rowH[last] += natural[i] - have
                }
            }
            val rowY = IntArray(t.rows.size + 1)
            for (r in t.rows.indices) rowY[r + 1] = rowY[r] + rowH[r]
            val placeables = measurables.mapIndexed { i, m ->
                val s = slots[i]
                val h = rowY[(s.row + s.rows).coerceAtMost(t.rows.size)] - rowY[s.row]
                m.measure(Constraints.fixed(widths[i].coerceAtLeast(1), h.coerceAtLeast(1)))
            }
            val width = colX.last()
            layout(width, rowY.last()) {
                placeables.forEachIndexed { i, pl -> pl.place(colX[slots[i].col], rowY[slots[i].row]) }
            }
        }
    }
}

@Composable
internal fun EmptyHint(text: String) {
    Text(text, color = Color.Gray, fontSize = 14.sp)
}

/** Labels for list paragraphs, recomputed when the document changes. */
@Composable
internal fun rememberListLabels(doc: WordDocument): Map<BlockPath, String> = remember(doc) { WordEditing.listLabels(doc) }
