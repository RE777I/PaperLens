package com.paperlens.app.excel

import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone
import kotlin.math.round

/**
 * Turns recognised cell text into a typed cell value: numbers with thousands separators and decimals
 * ("1,234.50", "1.234,50", "Rp 25.000"), percentages, currency amounts, negative amounts in brackets,
 * and dates written year first ("2026-11-02", "2026年11月2日"). Each keeps a number format that shows it
 * as it was printed. Everything else, and anything that only looks numeric (leading zeros, very long
 * digit strings such as account numbers), stays text.
 */
internal object CellValues {

    /** Which character a page uses as the decimal mark (the other one groups thousands). */
    enum class Decimal { POINT, COMMA }

    /** Votes over all numbers on the page; ambiguous ones ("1.234") don't count. */
    fun convention(texts: List<String>): Decimal {
        var point = 0
        var comma = 0
        for (t in texts) {
            val s = t.replace(" ", "")
            if (COMMA_DECIMAL.containsMatchIn(s)) comma++
            if (POINT_DECIMAL.containsMatchIn(s)) point++
            // Rupiah and euro amounts are usually grouped with dots.
            if (RUPIAH.containsMatchIn(s)) comma++
        }
        return if (comma > point) Decimal.COMMA else Decimal.POINT
    }

    fun parse(raw: String, dec: Decimal): XCell? {
        var s = normalise(raw.trim())
        if (s.isEmpty() || s.length > 40) return null
        date(s)?.let { return it }
        var negative = false
        if (s.startsWith("(") && s.endsWith(")")) { negative = true; s = s.substring(1, s.length - 1).trim() }
        if (s.startsWith("-")) { negative = !negative; s = s.substring(1).trim() }
        if (s.endsWith("-") && s.length > 1) { negative = !negative; s = s.dropLast(1).trim() }
        var prefix = ""
        var suffix = ""
        PREFIXES.firstOrNull { s.startsWith(it, ignoreCase = true) }?.let { p ->
            var rest = s.substring(p.length)
            if (p.equals("Rp", ignoreCase = true)) rest = rest.removePrefix(".")
            prefix = s.substring(0, p.length) + if (rest.startsWith(" ")) " " else ""
            s = rest.trim()
        }
        if (s.startsWith("-") && prefix.isNotEmpty()) { negative = !negative; s = s.substring(1).trim() }
        var percent = false
        if (s.endsWith("%")) { percent = true; s = s.dropLast(1).trim() }
        SUFFIXES.firstOrNull { s.endsWith(it, ignoreCase = true) }?.let { x ->
            val body = s.dropLast(x.length)
            suffix = (if (body.endsWith(" ")) " " else "") + s.takeLast(x.length)
            s = body.trim()
        }
        // A dash-like trailing ",-" / ".-" means "no cents" (Rp 25.000,-).
        if (s.endsWith(",-") || s.endsWith(".-") || s.endsWith("-,")) s = s.dropLast(2)
        if (!s.matches(DIGITS)) return null
        val digits = s.count { it.isDigit() }
        if (digits > 15) return null
        if (s.length > 1 && s[0] == '0' && s[1].isDigit()) return null // "007", "0812…": codes, not amounts
        // Which separator is the decimal mark.
        val lastDot = s.lastIndexOf('.')
        val lastComma = s.lastIndexOf(',')
        val decimalAt = when {
            lastDot >= 0 && lastComma >= 0 -> maxOf(lastDot, lastComma)
            lastDot < 0 && lastComma < 0 -> -1
            else -> {
                val at = maxOf(lastDot, lastComma)
                val sep = s[at]
                val count = s.count { it == sep }
                val after = s.length - at - 1
                when {
                    count > 1 -> -1 // repeated: thousands
                    after != 3 -> at // "12.5", "12,50": decimals
                    sep == ',' -> if (dec == Decimal.COMMA) at else -1
                    else -> if (dec == Decimal.COMMA) -1 else at
                }
            }
        }
        val intPart = (if (decimalAt >= 0) s.substring(0, decimalAt) else s).filter { it.isDigit() }
        val fracPart = if (decimalAt >= 0) s.substring(decimalAt + 1) else ""
        if (fracPart.any { !it.isDigit() }) return null
        val grouped = (if (decimalAt >= 0) s.substring(0, decimalAt) else s).any { it == '.' || it == ',' }
        // Groups must be three digits, or it isn't a number ("1,23,4").
        if (grouped && !(if (decimalAt >= 0) s.substring(0, decimalAt) else s).matches(GROUPED)) return null
        var value = "${intPart.ifEmpty { "0" }}.${fracPart.ifEmpty { "0" }}".toDouble()
        if (negative) value = -value
        if (percent) value /= 100
        val core = (if (grouped) "#,##0" else "0") + (if (fracPart.isNotEmpty()) "." + "0".repeat(fracPart.length) else "")
        val format = when {
            percent -> "$core%"
            prefix.isNotEmpty() || suffix.isNotEmpty() -> quote(prefix) + core + quote(suffix)
            grouped || fracPart.isNotEmpty() -> core
            else -> "General"
        }
        return XCell(CellValue.Number(if (percent) round(value * 1e12) / 1e12 else value), CellStyle(numFmt = format))
    }

    /** A currency sign or code on its own ("Rp.", "$", "IDR"). */
    fun isCurrencySign(s: String): Boolean = normalise(s.trim()).trimEnd('.', ' ').let { t -> PREFIXES.any { it.equals(t, ignoreCase = true) } }

    private fun quote(s: String) = if (s.isEmpty()) "" else "\"" + s.replace("\"", "") + "\""

    private fun date(s: String): XCell? {
        val iso = ISO_DATE.matchEntire(s)
        val cjk = CJK_DATE.matchEntire(s)
        val m = iso ?: cjk ?: return null
        val y = m.groupValues[1].toInt()
        val mo = m.groupValues[2].toInt()
        val d = m.groupValues[3].toInt()
        if (y !in 1900..2200 || mo !in 1..12 || d !in 1..31) return null
        val cal = GregorianCalendar(TimeZone.getTimeZone("UTC")).apply { clear(); set(y, mo - 1, d) }
        if (cal.get(Calendar.DAY_OF_MONTH) != d) return null
        val base = GregorianCalendar(TimeZone.getTimeZone("UTC")).apply { clear(); set(1899, 11, 30) }
        val serial = ((cal.timeInMillis - base.timeInMillis) / 86_400_000L).toDouble()
        val sep = if (iso != null) s.first { !it.isDigit() } else null
        val format = if (cjk != null) "yyyy\"年\"m\"月\"d\"日\"" else "yyyy${sep}mm${sep}dd"
        return XCell(CellValue.Number(serial), CellStyle(numFmt = format))
    }

    /** Full-width digits and signs (common in Chinese print) to ASCII; unusual minus signs to "-". */
    private fun normalise(s: String): String = buildString {
        for (ch in s) append(
            when (ch) {
                in '０'..'９' -> '0' + (ch - '０')
                '，' -> ','
                '．' -> '.'
                '％' -> '%'
                '－', '−', '–' -> '-'
                '（' -> '('
                '）' -> ')'
                '＄' -> '$'
                ' ', ' ', ' ' -> ' '
                else -> ch
            },
        )
    }

    private val PREFIXES = listOf("US$", "HK$", "NT$", "S$", "A$", "C$", "RMB", "IDR", "USD", "EUR", "SGD", "Rp", "RM", "$", "€", "£", "¥", "￥", "₩", "₹", "₫", "฿")
    private val SUFFIXES = listOf("元", "円", "원", "€", "₫", "IDR", "USD", "RMB", "EUR")
    private val DIGITS = Regex("[0-9][0-9.,]*|[.,][0-9]+")
    private val GROUPED = Regex("[0-9]{1,3}([.,][0-9]{3})*")
    private val COMMA_DECIMAL = Regex("(^|[^0-9.,])[0-9]{1,3}(\\.[0-9]{3})+,[0-9]{1,2}($|[^0-9])|(^|[^0-9.,])[0-9]+,[0-9]{1,2}($|[^0-9.,])")
    private val POINT_DECIMAL = Regex("(^|[^0-9.,])[0-9]{1,3}(,[0-9]{3})+\\.[0-9]+($|[^0-9])|(^|[^0-9.,])[0-9]+\\.[0-9]{1,2}($|[^0-9.,])")
    private val RUPIAH = Regex("(Rp|IDR)\\.?[0-9]{1,3}(\\.[0-9]{3})+", RegexOption.IGNORE_CASE)
    private val ISO_DATE = Regex("([0-9]{4})[-/.]([0-9]{1,2})[-/.]([0-9]{1,2})")
    private val CJK_DATE = Regex("([0-9]{4})\\s*年\\s*([0-9]{1,2})\\s*月\\s*([0-9]{1,2})\\s*日")
}
