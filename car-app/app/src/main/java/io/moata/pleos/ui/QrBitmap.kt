package io.moata.pleos.ui

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel


object QrBitmap {
    fun create(text: String, px: Int = 640): Bitmap {
        val hints = mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, px, px, hints)
        val pixels = IntArray(px * px) { i -> if (m.get(i % px, i / px)) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(pixels, px, px, Bitmap.Config.ARGB_8888)
    }
}
