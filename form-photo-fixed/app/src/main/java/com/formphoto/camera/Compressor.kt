package com.formphoto.camera

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream

object Compressor {
    class Out(val bytes: ByteArray, val w: Int, val h: Int, val padded: Boolean)

    private fun enc(b: Bitmap, q: Int): ByteArray {
        val o = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.JPEG, q, o)
        return o.toByteArray()
    }

    private fun load(cr: ContentResolver, uri: Uri, limit: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        var s = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (s * 2) >= limit) s *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = s }
        val bmp = cr.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, opts) }!!
        val orient = cr.openInputStream(uri)!!.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
        val deg = when (orient) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (deg == 0f) return bmp
        val m = Matrix().apply { postRotate(deg) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    /** Biggest picture whose JPEG size is <= maxB; pads up to minB if asked. */
    fun run(cr: ContentResolver, uri: Uri, minB: Int, maxB: Int, capPx: Int, pad: Boolean): Out? {
        var base = load(cr, uri, if (capPx > 0) capPx else 2400)
        val longSide = maxOf(base.width, base.height)
        if (capPx > 0 && longSide > capPx) {
            val k = capPx.toFloat() / longSide
            base = Bitmap.createScaledBitmap(
                base, (base.width * k).toInt().coerceAtLeast(1), (base.height * k).toInt().coerceAtLeast(1), true
            )
        }
        for (sc in doubleArrayOf(1.0, .85, .7, .55, .42, .32, .24, .18, .12)) {
            val bmp = if (sc == 1.0) base else Bitmap.createScaledBitmap(
                base, (base.width * sc).toInt().coerceAtLeast(1), (base.height * sc).toInt().coerceAtLeast(1), true
            )
            val lo = enc(bmp, 5)
            if (lo.size > maxB) continue
            val hi = enc(bmp, 95)
            var best = hi
            if (hi.size > maxB) {
                var a = 5
                var z = 95
                best = lo
                repeat(7) {
                    val m = (a + z) / 2
                    val b = enc(bmp, m)
                    if (b.size <= maxB) { best = b; a = m } else z = m
                }
            }
            var padded = false
            if (best.size < minB && pad) {
                best = best + ByteArray(minB - best.size + 16)
                padded = true
            }
            return Out(best, bmp.width, bmp.height, padded)
        }
        return null
    }
}
