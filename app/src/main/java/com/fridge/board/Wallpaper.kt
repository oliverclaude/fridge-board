package com.fridge.board

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

object Wallpaper {
    private const val MAX_EDGE = 2560

    fun exists(context: Context): Boolean {
        val file = file(context)
        return file.isFile && file.length() > 0L
    }

    fun load(context: Context): Bitmap? {
        val custom = file(context)
        if (custom.isFile && custom.length() > 0L) {
            BitmapFactory.decodeFile(custom.absolutePath)?.let { return it }
        }
        return context.assets.open("wallpaper.jpg").use { BitmapFactory.decodeStream(it) }
    }

    /** Copies a chosen photo into app storage. The built-in photo stays in the apk. */
    fun import(context: Context, uri: Uri): Boolean {
        val bitmap = decode(context, uri) ?: return false
        return try {
            val tmp = File(context.filesDir, "wallpaper.jpg.tmp")
            val wrote = FileOutputStream(tmp).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            if (!wrote) {
                tmp.delete()
                false
            } else {
                val dest = file(context)
                if (!tmp.renameTo(dest)) {
                    tmp.copyTo(dest, overwrite = true)
                    tmp.delete()
                }
                BoardStore.bumpWallpaper()
                true
            }
        } catch (_: Exception) {
            false
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    fun clear(context: Context) {
        file(context).delete()
        BoardStore.bumpWallpaper()
    }

    private fun file(context: Context) = File(context.filesDir, "wallpaper.jpg")

    private fun decode(context: Context, uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val degrees = rotation(context, uri)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MAX_EDGE)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bitmap = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null
        if (degrees != 0) {
            val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) bitmap.recycle()
            bitmap = rotated
        }
        val longEdge = max(bitmap.width, bitmap.height)
        if (longEdge > MAX_EDGE) {
            val scale = MAX_EDGE.toFloat() / longEdge
            val scaled = Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            )
            if (scaled != bitmap) bitmap.recycle()
            bitmap = scaled
        }
        return bitmap
    }

    private fun rotation(context: Context, uri: Uri): Int = try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            when (
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            ) {
                ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> 270
                else -> 0
            }
        } ?: 0
    } catch (_: Exception) {
        0
    }

    private fun sampleSize(width: Int, height: Int, maxEdge: Int): Int {
        var sample = 1
        var wide = width
        var high = height
        while (wide / 2 >= maxEdge || high / 2 >= maxEdge) {
            wide /= 2
            high /= 2
            sample *= 2
        }
        return sample
    }
}
