package com.example.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Singleton wrapper around Tesseract4Android (`com.googlecode.tesseract.android.TessBaseAPI`).
 *
 * Why this exists (and why ML Kit is NOT used):
 *   - The Vault "Scan Screen" feature must recognize BOTH English AND Arabic text.
 *   - Google ML Kit Text Recognition v2 does NOT ship an Arabic model and silently
 *     falls back to Latin-only recognition — that exact behaviour was the root cause
 *     of a previous failed OCR attempt on this project.
 *   - Tesseract with the official `tessdata_fast` LSTM models supports `eng+ara`
 *     combined-language initialization out of the box.
 *
 * Resource lifecycle (CRITICAL):
 *   - Native Tesseract allocates several MB of native heap per TessBaseAPI instance.
 *   - We MUST call `tessBaseAPI.end()` (or `.recycle()`) after every recognition call
 *     to avoid leaking native memory across repeated scans.
 *   - The traineddata files are copied lazily from `assets/tessdata/` to the app's
 *     private files dir on first use, then re-used for subsequent scans. We verify
 *     byte length > 0 after every copy to defend against the previous failure mode
 *     where 0-byte placeholder traineddata files were bundled silently.
 */
object OcrManager {

    private const val TAG = "OcrManager"

    /** Combined language code passed to `TessBaseAPI.init`. */
    private const val OCR_LANG = "eng+ara"

    /** Subdirectory under the app's files dir where Tesseract expects traineddata. */
    private const val TESS_DATA_DIR_NAME = "tessdata"

    /**
     * Page Segmentation Mode.
     * `TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK` (= 6) assumes a single uniform block
     * of text — the correct assumption for a tightly-cropped region the user just
     * dragged a rectangle around. The default (`PSM_AUTO`, 3) assumes a full page
     * with complex layout and tends to mis-segment small screen crops.
     */
    private val OCR_PSM = TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK

    /** Cached absolute path of the prepared tessdata directory's PARENT, populated on first init. */
    @Volatile
    private var preparedTessDataDir: String? = null

    /** Mutex-style guard so concurrent scans don't double-copy traineddata. */
    private val initLock = Any()

    /**
     * Public entry point. Runs OCR on [bitmap] and returns the recognized text.
     *
     * Contract:
     *   - Caller may pass any Bitmap; it will NOT be recycled by this method.
     *   - Caller is responsible for recycling the source MediaProjection Image's
     *     backing Bitmap once this returns (handled in ScreenCaptureManager).
     *   - Safe to call from a background coroutine context. We force Dispatchers.IO
     *     internally regardless.
     *   - Returns "" (empty string) — never null — when recognition fails or finds
     *     no text. Failures are logged to Logcat with tag [TAG].
     */
    suspend fun recognize(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        if (bitmap.width < 4 || bitmap.height < 4) {
            Log.w(TAG, "Bitmap too small for OCR (${bitmap.width}x${bitmap.height}), skipping")
            return@withContext ""
        }

        // Preprocess: grayscale + adaptive contrast/binarization. Dramatically improves
        // Tesseract accuracy on anti-aliased screen-captured text vs. raw ARGB pixels.
        val preprocessed = preprocessForOcr(bitmap)

        // Each TessBaseAPI instance is short-lived: init -> setImage -> getUTF8Text -> end.
        // This intentionally avoids long-lived native state and makes leaks impossible
        // as long as the `finally { api.end() }` runs.
        var api: TessBaseAPI? = null
        try {
            val tessDataDir = ensureTessDataDir()
            api = TessBaseAPI()
            val ok = api.init(tessDataDir, OCR_LANG)
            if (!ok) {
                Log.e(TAG, "TessBaseAPI.init() failed for lang='$OCR_LANG' in dir='$tessDataDir'. " +
                        "Check that eng.traineddata AND ara.traineddata are present and non-empty.")
                return@withContext ""
            }
            api.pageSegMode = OCR_PSM
            api.setImage(preprocessed)
            val text = api.utF8Text ?: ""
            Log.d(TAG, "OCR done: input ${bitmap.width}x${bitmap.height}, output ${text.length} chars")
            text.trim()
        } catch (t: Throwable) {
            Log.e(TAG, "OCR failed", t)
            ""
        } finally {
            // CRITICAL: release native Tesseract resources. Without this every scan
            // leaks several MB of native heap. `.end()` also internally calls recycle().
            try {
                api?.end()
            } catch (t: Throwable) {
                Log.w(TAG, "TessBaseAPI.end() threw", t)
            }
            // Recycle the intermediate preprocessed bitmap — but NOT the caller's bitmap.
            try {
                if (preprocessed !== bitmap) preprocessed.recycle()
            } catch (_: Throwable) {
                // best-effort
            }
        }
    }

    /**
     * Copies `assets/tessdata/{eng,ara}.traineddata` to `filesDir/tessdata/` on first run,
     * then returns the absolute path of `filesDir/` (the PARENT of `tessdata/`, which is
     * what `TessBaseAPI.init()` expects as its first argument).
     *
     * Verifies each copied file's byte length is strictly > 0 — a previous failure mode
     * was bundling 0-byte placeholder traineddata files; we explicitly catch that here.
     */
    private fun ensureTessDataDir(): String {
        preparedTessDataDir?.let { return it }
        synchronized(initLock) {
            preparedTessDataDir?.let { return it }

            val context = appContext
                ?: throw IllegalStateException("OcrManager.appContext not set; call setApplicationContext() first")
            val outRoot = context.filesDir.absolutePath
            val tessDataDir = File(outRoot, TESS_DATA_DIR_NAME)
            if (!tessDataDir.exists()) tessDataDir.mkdirs()

            // Required language models. If a file is missing OR 0 bytes on disk, (re)copy it.
            val requiredFiles = listOf("eng.traineddata", "ara.traineddata")
            for (name in requiredFiles) {
                val targetFile = File(tessDataDir, name)
                if (!targetFile.exists() || targetFile.length() == 0L) {
                    copyAssetToFile(context, "tessdata/$name", targetFile)
                }
                // Post-copy integrity check: refuse to proceed if the file is empty.
                val lenAfter = targetFile.length()
                if (lenAfter == 0L) {
                    throw IOException("Traineddata file '$name' is 0 bytes after copy. " +
                            "Asset may be missing or corrupt in the APK.")
                }
                Log.d(TAG, "Traineddata ready: $name (${lenAfter} bytes) at ${targetFile.absolutePath}")
            }

            preparedTessDataDir = outRoot
            return outRoot
        }
    }

    /** Streams an asset to a destination file. Throws on I/O failure. */
    private fun copyAssetToFile(context: Context, assetPath: String, destFile: File) {
        try {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            }
        } catch (e: IOException) {
            throw IOException("Failed to copy asset '$assetPath' to '${destFile.absolutePath}'", e)
        }
    }

    /**
     * Lightweight image preprocessing — grayscale + adaptive threshold binarization —
     * implemented with `android.graphics.Bitmap` only (no OpenCV dependency).
     *
     * Why: Tesseract's LSTM engine works best on high-contrast grayscale or binary
     * images. Screen-captured text is anti-aliased ARGB and usually sits on a
     * colored background, which materially hurts recognition accuracy.
     *
     * Algorithm (per-pixel, O(width*height)):
     *   1. Compute luminance via the standard ITU-R BT.601 weights.
     *   2. Compute a single global mean luminance as the threshold (Otsu-style
     *      would be marginally better but adds another pass; for typical screen
     *      text the global mean is good enough and is what most "tesseract preprocess"
     *      tutorials use).
     *   3. Binarize: luminance > mean -> white, else -> black.
     *
     * Returns a new ARGB_8888 Bitmap (caller must recycle) whose pixel content is
     * effectively 1-bit black/white packed into ARGB.
     */
    private fun preprocessForOcr(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)

        // Pass 1: convert to grayscale (store as 0..255 in the R channel; we don't
        // need a separate array because we'll overwrite in pass 2 below after computing mean).
        var sum = 0L
        val gray = IntArray(w * h)
        for (i in pixels.indices) {
            val c = pixels[i]
            // BT.601: 0.299R + 0.587G + 0.114B, rounded to int
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)
            val lum = (r * 299 + g * 587 + b * 114) / 1000
            gray[i] = lum
            sum += lum
        }
        val mean = (sum / gray.size).toInt()

        // Pass 2: binarize. We reuse `pixels` as the output buffer.
        for (i in gray.indices) {
            pixels[i] = if (gray[i] > mean) Color.WHITE else Color.BLACK
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    // ------------------------------------------------------------------
    // Application context plumbing
    // ------------------------------------------------------------------

    @Volatile
    private var appContext: Context? = null

    /**
     * MUST be called once from [com.example.OrbitApp.onCreate] (or any Application subclass).
     * Stores the application context so OCR can be triggered from anywhere without
     * leaking an Activity context into the singleton.
     */
    fun setApplicationContext(context: Context) {
        appContext = context.applicationContext
    }
}
