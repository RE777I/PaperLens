package com.paperlens.app.excel

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Excel number formats, enough to show a sheet as Excel would: General, fixed decimals, thousands
 * separators, percentages, currency and literal text around the number, scientific, negative sections,
 * and dates / times. Unusual parts (fractions, fill characters, conditions) fall back to plain numbers.
 */
object NumberFormat {

    /** Built-in format ids that files reference without writing the code out. */
    val BUILT_IN: Map<Int, String> = mapOf(
        0 to "General", 1 to "0", 2 to "0.00", 3 to "#,##0", 4 to "#,##0.00", 9 to "0%", 10 to "0.00%",
        11 to "0.00E+00", 12 to "# ?/?", 13 to "# ??/??", 14 to "yyyy-mm-dd", 15 to "d-mmm-yy", 16 to "d-mmm", 17 to "mmm-yy",
        18 to "h:mm AM/PM", 19 to "h:mm:ss AM/PM", 20 to "h:mm", 21 to "h:mm:ss", 22 to "yyyy-mm-dd h:mm",
        37 to "#,##0 ;(#,##0)", 38 to "#,##0 ;[Red](#,##0)", 39 to "#,##0.00;(#,##0.00)", 40 to "#,##0.00;[Red](#,##0.00)",
        45 to "mm:ss", 46 to "[h]:mm:ss", 47 to "mm:ss.0", 48 to "##0.0E+0", 49 to "@",
    )

    fun format(value: Double, code: String, date1904: Boolean = false): String {
        if (value.isNaN()) return "#NUM!"
        if (value.isInfinite()) return "#DIV/0!"
        if (code.isBlank() || code.equals("General", ignoreCase = true) || code == "@") return general(value)
        val sections = splitSections(code)
        val (section, negativeHandled) = when {
            value < 0 && sections.size >= 2 -> sections[1] to true
            value == 0.0 && sections.size >= 3 -> sections[2] to false
            else -> sections[0] to false
        }
        val v = if (negativeHandled) abs(value) else value
        val clean = section.replace(BRACKETS) { m ->
            // [$€-407] → "€"; colours and conditions are dropped; [h] (elapsed hours) stays.
            val inner = m.value.substring(1, m.value.length - 1)
            when {
                inner.startsWith("$") -> "\"" + inner.substring(1).substringBefore('-') + "\""
                inner.matches(ELAPSED) -> m.value
                else -> ""
            }
        }
        return try {
            if (isDate(clean)) date(v, clean, date1904) else number(v, clean)
        } catch (_: RuntimeException) {
            general(value)
        }
    }

    /** The number as typed into a cell: no grouping, no trailing zeros. */
    fun plain(value: Double): String {
        if (value == floor(value) && abs(value) < 1e15) return value.toLong().toString()
        return BigDecimal(value).round(java.math.MathContext(15)).stripTrailingZeros().toPlainString()
    }

    /** Excel's General: up to 11 characters, scientific for very large or small numbers. */
    fun general(value: Double): String {
        if (value == 0.0) return "0"
        val a = abs(value)
        if (a >= 1e11 || a < 1e-9) return String.format(Locale.ROOT, "%.5E", value).replace(Regex("\\.?0+E"), "E").replace("E+0", "E+").replace("E-0", "E-")
        val bd = BigDecimal(value).round(java.math.MathContext(10)).stripTrailingZeros()
        return if (bd.scale() <= 0) bd.toBigInteger().toString() else bd.toPlainString()
    }

    private fun splitSections(code: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quoted = false
        var i = 0
        while (i < code.length) {
            val ch = code[i]
            when {
                ch == '"' -> { quoted = !quoted; cur.append(ch) }
                ch == '\\' && i + 1 < code.length -> { cur.append(ch).append(code[i + 1]); i++ }
                ch == ';' && !quoted -> { out += cur.toString(); cur.clear() }
                else -> cur.append(ch)
            }
            i++
        }
        out += cur.toString()
        return out
    }

    /** Date / time format: has y, d, h or s outside quotes (m alone is ambiguous but only appears with them). */
    private fun isDate(code: String): Boolean {
        val bare = stripLiterals(code).lowercase()
        return bare.any { it == 'y' || it == 'd' || it == 'h' || it == 's' } ||
            (bare.contains('m') && !bare.any { it == '0' || it == '#' || it == '?' })
    }

    private fun stripLiterals(code: String): String = code.replace(Regex("\"[^\"]*\"|\\\\.|_.|\\*."), "")

    // ---- numbers ---------------------------------------------------------------------------------------

    private fun number(value: Double, code: String): String {
        // Parts: literal text before, the digit pattern, literal text after.
        val tokens = tokens(code)
        val first = tokens.indexOfFirst { it.digit }
        if (first < 0) return tokens.joinToString("") { it.text }.ifEmpty { general(value) }
        var last = first
        while (last + 1 < tokens.size && (tokens[last + 1].digit || (tokens[last + 1].text in setOf(".", ",") && tokens.drop(last + 1).any { it.digit }))) last++
        val pattern = tokens.subList(first, last + 1).joinToString("") { it.text }
        val prefix = tokens.subList(0, first).joinToString("") { it.text }
        val suffix = tokens.subList(last + 1, tokens.size).joinToString("") { it.text }
        val percent = (prefix + suffix).count { it == '%' }
        var v = value
        repeat(percent) { v *= 100 }
        val sci = Regex("[eE][+-]").find(pattern)
        val body = if (sci != null) scientific(v, pattern, sci.range.first) else fixed(v, pattern)
        // The minus goes before any currency sign ("-$5.00"); a negative section has none of its own.
        val sign = if (body.startsWith("-")) "-" else ""
        return sign + prefix + body.removePrefix("-") + suffix
    }

    private class Token(val text: String, val digit: Boolean)

    private fun tokens(code: String): List<Token> {
        val out = ArrayList<Token>()
        var i = 0
        while (i < code.length) {
            val ch = code[i]
            when {
                ch == '"' -> {
                    val end = code.indexOf('"', i + 1).let { if (it < 0) code.length else it }
                    out += Token(code.substring(i + 1, end), false)
                    i = end
                }
                ch == '\\' && i + 1 < code.length -> { out += Token(code[i + 1].toString(), false); i++ }
                ch == '_' && i + 1 < code.length -> { out += Token(" ", false); i++ }
                ch == '*' && i + 1 < code.length -> i++
                ch == '0' || ch == '#' || ch == '?' -> out += Token(ch.toString(), true)
                ch == 'E' || ch == 'e' -> {
                    if (i + 1 < code.length && (code[i + 1] == '+' || code[i + 1] == '-')) { out += Token(code.substring(i, i + 2), true); i++ }
                    else out += Token(ch.toString(), false)
                }
                else -> out += Token(ch.toString(), false)
            }
            i++
        }
        return out
    }

    private fun fixed(value: Double, pattern: String): String {
        val dot = pattern.indexOf('.')
        val intPart = if (dot >= 0) pattern.substring(0, dot) else pattern
        val fracPart = if (dot >= 0) pattern.substring(dot + 1).filter { it == '0' || it == '#' || it == '?' } else ""
        // Commas after the last digit scale by 1000 each.
        val scale = intPart.length - intPart.trimEnd(',').length
        var v = abs(value)
        repeat(scale) { v /= 1000 }
        val grouping = intPart.trimEnd(',').contains(',')
        val minInt = intPart.count { it == '0' }
        val minFrac = fracPart.count { it == '0' }
        val maxFrac = fracPart.length
        val rounded = BigDecimal(v).setScale(maxFrac, RoundingMode.HALF_UP)
        val plain = rounded.toPlainString()
        var ip = plain.substringBefore('.')
        var fp = if (plain.contains('.')) plain.substringAfter('.') else ""
        while (fp.length > minFrac && fp.endsWith('0')) fp = fp.dropLast(1)
        if (ip == "0" && minInt == 0) ip = ""
        ip = ip.padStart(minInt, '0')
        if (grouping && ip.length > 3) ip = ip.reversed().chunked(3).joinToString(",").reversed()
        val out = if (fp.isNotEmpty() || (dot >= 0 && minFrac > 0)) "$ip.$fp" else if (dot >= 0 && maxFrac == 0 && pattern.endsWith('.')) "$ip." else ip
        return if (value < 0 && out.any { it in '1'..'9' }) "-$out" else out.ifEmpty { "0" }
    }

    private fun scientific(value: Double, pattern: String, at: Int): String {
        val mant = pattern.substring(0, at)
        val expDigits = pattern.substring(at + 2).count { it == '0' }.coerceAtLeast(1)
        val decimals = mant.substringAfter('.', "").count { it == '0' || it == '#' }
        if (value == 0.0) return fixed(0.0, mant) + "E+" + "0".repeat(expDigits)
        var exp = floor(kotlin.math.log10(abs(value))).toInt()
        var m = value / Math.pow(10.0, exp.toDouble())
        if (abs(BigDecimal(m).setScale(decimals, RoundingMode.HALF_UP).toDouble()) >= 10) { m /= 10; exp++ }
        return fixed(m, mant) + "E" + (if (exp < 0) "-" else "+") + abs(exp).toString().padStart(expDigits, '0')
    }

    // ---- dates -------------------------------------------------------------------------------------------

    private val MONTHS = arrayOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
    private val DAYS = arrayOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")

    /** Serial day number → calendar time (UTC, so no daylight-saving shifts). */
    fun calendar(serial: Double, date1904: Boolean): Calendar {
        val cal = GregorianCalendar(TimeZone.getTimeZone("UTC"))
        cal.clear()
        if (date1904) cal.set(1904, 0, 1) else cal.set(1899, 11, 30)
        var days = floor(serial).toLong()
        // Excel's 1900 calendar has a 29 February 1900 that never was: serials before it are one day off.
        if (!date1904 && serial < 61) days += 1
        val ms = ((serial - floor(serial)) * 86_400_000.0).roundToLong()
        cal.timeInMillis = cal.timeInMillis + days * 86_400_000L + ms
        return cal
    }

    private fun date(serial: Double, code: String, date1904: Boolean): String {
        val cal = calendar(serial, date1904)
        val ampm = code.contains("AM/PM", ignoreCase = true) || code.contains("A/P", ignoreCase = true)
        val out = StringBuilder()
        var i = 0
        var lastWasHour = false
        val lower = code.lowercase()
        fun run(ch: Char): Int { var j = i; while (j < lower.length && lower[j] == ch) j++; return j - i }
        while (i < code.length) {
            val ch = lower[i]
            when {
                code[i] == '"' -> {
                    val end = code.indexOf('"', i + 1).let { if (it < 0) code.length else it }
                    out.append(code, i + 1, end); i = end + 1
                }
                code[i] == '\\' && i + 1 < code.length -> { out.append(code[i + 1]); i += 2 }
                ch == '_' || ch == '*' -> i += 2
                code.startsWith("AM/PM", i, ignoreCase = true) -> { out.append(if (cal.get(Calendar.HOUR_OF_DAY) < 12) "AM" else "PM"); i += 5 }
                code.startsWith("A/P", i, ignoreCase = true) -> { out.append(if (cal.get(Calendar.HOUR_OF_DAY) < 12) "A" else "P"); i += 3 }
                ch == '[' -> {
                    val end = code.indexOf(']', i).let { if (it < 0) code.length - 1 else it }
                    val inner = lower.substring(i + 1, end)
                    val total = serial * 24
                    when (inner.firstOrNull()) {
                        'h' -> out.append(floor(total).toLong().toString().padStart(inner.length, '0'))
                        'm' -> out.append(floor(total * 60).toLong().toString().padStart(inner.length, '0'))
                        's' -> out.append(floor(total * 3600).toLong().toString().padStart(inner.length, '0'))
                    }
                    lastWasHour = inner.startsWith("h")
                    i = end + 1
                }
                ch == 'y' -> { val n = run('y'); val y = cal.get(Calendar.YEAR); out.append(if (n <= 2) (y % 100).toString().padStart(2, '0') else y.toString()); i += n }
                ch == 'd' -> {
                    val n = run('d')
                    out.append(
                        when (n) {
                            1 -> cal.get(Calendar.DAY_OF_MONTH).toString()
                            2 -> cal.get(Calendar.DAY_OF_MONTH).toString().padStart(2, '0')
                            3 -> DAYS[cal.get(Calendar.DAY_OF_WEEK) - 1].take(3)
                            else -> DAYS[cal.get(Calendar.DAY_OF_WEEK) - 1]
                        },
                    )
                    i += n
                }
                ch == 'm' -> {
                    val n = run('m')
                    // Minutes right after hours or right before seconds; months otherwise.
                    val rest = lower.substring(i + n).trimStart(':', ' ', '.')
                    val minute = n <= 2 && (lastWasHour || rest.startsWith("s"))
                    out.append(
                        when {
                            minute -> cal.get(Calendar.MINUTE).toString().padStart(n, '0')
                            n == 1 -> (cal.get(Calendar.MONTH) + 1).toString()
                            n == 2 -> (cal.get(Calendar.MONTH) + 1).toString().padStart(2, '0')
                            n == 3 -> MONTHS[cal.get(Calendar.MONTH)].take(3)
                            n == 5 -> MONTHS[cal.get(Calendar.MONTH)].take(1)
                            else -> MONTHS[cal.get(Calendar.MONTH)]
                        },
                    )
                    lastWasHour = false
                    i += n
                    continue
                }
                ch == 'h' -> {
                    val n = run('h')
                    var hr = cal.get(Calendar.HOUR_OF_DAY)
                    if (ampm) { hr %= 12; if (hr == 0) hr = 12 }
                    out.append(hr.toString().padStart(n.coerceAtMost(2), '0'))
                    i += n
                    lastWasHour = true
                    continue
                }
                ch == 's' -> {
                    val n = run('s')
                    out.append(cal.get(Calendar.SECOND).toString().padStart(n.coerceAtMost(2), '0'))
                    i += n
                    // Fractions of a second: ".0", ".00".
                    if (i < lower.length && lower[i] == '.' && i + 1 < lower.length && lower[i + 1] == '0') {
                        var z = 0; while (i + 1 + z < lower.length && lower[i + 1 + z] == '0') z++
                        val frac = (cal.get(Calendar.MILLISECOND) / Math.pow(10.0, (3 - z).toDouble())).toInt()
                        out.append('.').append(frac.toString().padStart(z, '0'))
                        i += 1 + z
                    }
                }
                else -> { out.append(code[i]); i++ }
            }
            // "h:mm": the minutes test looks back over separators to the hours.
            if (ch != ':' && ch != ' ' && ch != '[') lastWasHour = false
        }
        return out.toString()
    }

    private val BRACKETS = Regex("\\[[^\\]]*\\]")
    private val ELAPSED = Regex("[hHmMsS]+")
}
