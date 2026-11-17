package com.paperlens.app.imaging

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Makes QR codes (Tools → Create Code), with ZXing's encoder. */
object QrCodes {

    /** Longest text offered for a code; denser codes are hard to scan from a phone screen. */
    const val MAX_LENGTH = 1200

    /**
     * [text] as a black-on-white QR code about [size] pixels wide (each module a whole number of pixels,
     * so the edges stay crisp), or null when the text does not fit in a code.
     */
    fun bitmap(text: String, size: Int): Bitmap? {
        val hints = mutableMapOf<EncodeHintType, Any>(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 2,
        )
        // Plain ASCII needs no character-set marker (some older scanners stumble on it); other text is UTF-8.
        if (text.any { it.code > 0x7F }) hints[EncodeHintType.CHARACTER_SET] = "UTF-8"
        val matrix = try {
            // Size 0 gives one cell per module; it is scaled up below.
            QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        } catch (e: WriterException) {
            return null
        }
        val cells = matrix.width
        val scale = (size / cells).coerceAtLeast(1)
        val side = cells * scale
        val pixels = IntArray(side * side)
        for (y in 0 until side) {
            val row = y / scale
            for (x in 0 until side) pixels[y * side + x] = if (matrix[x / scale, row]) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
    }

    /** The text a phone's camera reads as "join this Wi-Fi network". */
    fun wifi(network: String, password: String): String {
        fun esc(s: String) = s.replace(Regex("""([\\;,:"])"""), """\\$1""")
        val security = if (password.isEmpty()) "nopass" else "WPA"
        return "WIFI:T:$security;S:${esc(network)};P:${esc(password)};;"
    }
}
