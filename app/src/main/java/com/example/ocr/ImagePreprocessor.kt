package com.example.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import kotlin.math.max
import kotlin.math.min

/**
 * Lightweight, dependency-free preprocessing to improve Tesseract accuracy on
 * screen-captured/video-frame text. Deliberately avoids a full OpenCV dependency:
 * everything here runs on a plain [Bitmap] using [ColorMatrix] (fast, hardware-friendly)
 * for grayscale + contrast, and a manual pixel pass for binarization.
 */
object ImagePreprocessor {

    /**
     * Runs grayscale -> contrast stretch -> binarization on [source] and returns a new bitmap.
     * [source] is never mutated or recycled by this function; the caller owns its lifecycle.
     */
    fun preprocess(source: Bitmap): Bitmap {
        val grayscale = toGrayscale(source)
        val contrasted = stretchContrast(grayscale)
        if (contrasted !== grayscale) grayscale.recycle()
        val binarized = binarize(contrasted, OcrConstants.BINARIZATION_THRESHOLD)
        if (binarized !== contrasted) contrasted.recycle()
        return binarized
    }

    private fun toGrayscale(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val colorMatrix = ColorMatrix().apply { setSaturation(0f) }
        paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
        canvas.drawBitmap(source, 0f, 0f, paint)
        return result
    }

    /**
     * Simple linear contrast stretch based on the actual min/max luminance found in the
     * image, so faint UI text gets pushed toward pure black/white before binarization.
     */
    private fun stretchContrast(grayscale: Bitmap): Bitmap {
        val width = grayscale.width
        val height = grayscale.height
        val pixels = IntArray(width * height)
        grayscale.getPixels(pixels, 0, width, 0, 0, width, height)

        var minLum = 255
        var maxLum = 0
        for (pixel in pixels) {
            val lum = pixel and 0xFF // R=G=B after grayscale conversion
            if (lum < minLum) minLum = lum
            if (lum > maxLum) maxLum = lum
        }

        val range = maxLum - minLum
        if (range <= 0) {
            // Flat image (e.g. blank region) — nothing to stretch, return as-is.
            return grayscale
        }

        val scale = 255.0 / range
        for (i in pixels.indices) {
            val lum = pixels[i] and 0xFF
            val stretched = (((lum - minLum) * scale).toInt()).coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (stretched shl 16) or (stretched shl 8) or stretched
        }

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }

    /**
     * Hard threshold binarization: pixels above [threshold] become white, below become black.
     * This mirrors what adaptive contrast+binarization pipelines do for OCR pre-processing,
     * without pulling in OpenCV — screen UI text is high-contrast enough that a global
     * threshold (applied after the contrast stretch above) works reliably.
     */
    private fun binarize(source: Bitmap, threshold: Int): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        val clampedThreshold = max(0, min(255, threshold))
        for (i in pixels.indices) {
            val lum = pixels[i] and 0xFF
            val value = if (lum >= clampedThreshold) 255 else 0
            pixels[i] = (0xFF shl 24) or (value shl 16) or (value shl 8) or value
        }

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }
}
