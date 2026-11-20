package com.fridge.board

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import kotlin.math.max
import kotlin.math.min

object IconFactory {
    private val cache = object : LruCache<String, Bitmap>(32 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun app(pm: PackageManager, pkg: String, sizePx: Int, plate: Int): Bitmap? {
        val key = "a|$pkg|$sizePx|$plate"
        cache.get(key)?.let { return it }
        val drawable = try {
            pm.getApplicationIcon(pkg)
        } catch (_: Exception) {
            return null
        }
        val raw = render(drawable, 256)
        val plated = recolorEdgeWhite(raw, plate)
        val out = mask(plated, sizePx, plate)
        cache.put(key, out)
        return out
    }

    fun home(sizePx: Int, plate: Int): Bitmap {
        val key = "h|$sizePx|$plate"
        cache.get(key)?.let { return it }
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        clipRound(canvas, sizePx)
        paint.color = plate
        canvas.drawPaint(paint)
        paint.color = 0xFFF2F2F2.toInt()
        val s = sizePx.toFloat()
        val house = Path().apply {
            moveTo(s * 0.50f, s * 0.22f)
            lineTo(s * 0.80f, s * 0.46f)
            lineTo(s * 0.70f, s * 0.46f)
            lineTo(s * 0.70f, s * 0.78f)
            lineTo(s * 0.58f, s * 0.78f)
            lineTo(s * 0.58f, s * 0.58f)
            lineTo(s * 0.42f, s * 0.58f)
            lineTo(s * 0.42f, s * 0.78f)
            lineTo(s * 0.30f, s * 0.78f)
            lineTo(s * 0.30f, s * 0.46f)
            lineTo(s * 0.20f, s * 0.46f)
            close()
        }
        canvas.drawPath(house, paint)
        cache.put(key, out)
        return out
    }

    private fun render(drawable: Drawable, size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val copy = drawable.mutate()
        if (copy is BitmapDrawable) {
            copy.isFilterBitmap = true
        }
        if (copy is AdaptiveIconDrawable) {
            // Draw the layers ourselves. The system mask leaves a light rim.
            drawAdaptive(canvas, copy, size)
        } else {
            val inset = (size * 0.08f).toInt()
            copy.setBounds(inset, inset, size - inset, size - inset)
            copy.draw(canvas)
        }
        return bitmap
    }

    private fun drawAdaptive(canvas: Canvas, icon: AdaptiveIconDrawable, size: Int) {
        val background = icon.background
        val foreground = icon.foreground
        if (background == null && foreground == null) {
            icon.setBounds(0, 0, size, size)
            icon.draw(canvas)
            return
        }
        // The masked icon shows the center 72dp of a 108dp layer. Zoom by that
        // amount so the glyph stays the same size, with no mask stroke.
        val extra = size / 4
        background?.setBounds(-extra, -extra, size + extra, size + extra)
        foreground?.setBounds(-extra, -extra, size + extra, size + extra)
        background?.draw(canvas)
        foreground?.draw(canvas)
    }

    /**
     * Recolors light pixels that touch the icon edge. Interior whites, such as
     * the date on Calendar or the hole in the Maps pin, stay as they are.
     */
    private fun recolorEdgeWhite(source: Bitmap, plate: Int): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val seen = BooleanArray(pixels.size)
        val queue = ArrayDeque<Int>()
        fun push(index: Int) {
            if (index !in pixels.indices || seen[index] || !isLightPlate(pixels[index])) return
            seen[index] = true
            queue.add(index)
        }
        for (x in 0 until width) {
            push(x)
            push((height - 1) * width + x)
        }
        for (y in 0 until height) {
            push(y * width)
            push(y * width + width - 1)
        }
        while (queue.isNotEmpty()) {
            val index = queue.removeFirst()
            pixels[index] = plate
            val x = index % width
            val y = index / width
            if (x > 0) push(index - 1)
            if (x + 1 < width) push(index + 1)
            if (y > 0) push(index - width)
            if (y + 1 < height) push(index + width)
        }
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, width, 0, 0, width, height)
        return out
    }

    private fun isLightPlate(color: Int): Boolean {
        val alpha = color ushr 24
        if (alpha < 48) return true
        val red = (color shr 16) and 0xFF
        val green = (color shr 8) and 0xFF
        val blue = color and 0xFF
        val spread = max(red, max(green, blue)) - min(red, min(green, blue))
        if (spread > 22) return false
        val luminance = (red + green + blue) / 3
        return luminance >= 160
    }

    private fun mask(source: Bitmap, size: Int, plate: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val radius = size * 0.28f
        val rect = RectF(0f, 0f, size.toFloat(), size.toFloat())
        paint.color = plate
        canvas.drawRoundRect(rect, radius, radius, paint)
        // Cover any leftover light pixel on the system icon's outer edge.
        val inset = max(1f, size * 0.012f)
        val inner = RectF(inset, inset, size - inset, size - inset)
        canvas.save()
        canvas.clipPath(Path().apply {
            addRoundRect(inner, (radius - inset).coerceAtLeast(0f), (radius - inset).coerceAtLeast(0f), Path.Direction.CW)
        })
        canvas.drawBitmap(source, null, inner, paint)
        canvas.restore()
        return out
    }

    private fun clipRound(canvas: Canvas, size: Int) {
        val radius = size * 0.28f
        val path = Path().apply {
            addRoundRect(
                RectF(0f, 0f, size.toFloat(), size.toFloat()),
                radius,
                radius,
                Path.Direction.CW,
            )
        }
        canvas.clipPath(path)
    }
}
