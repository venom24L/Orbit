package com.example.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Thin wrapper around Tesseract4Android's [TessBaseAPI].
 *
 * Uses Tesseract4Android (github.com/adaptech-cz/Tesseract4Android) rather than Google ML Kit,
 * because ML Kit's on-device text recognizer has no Arabic model — that gap was the root cause
 * of a previous failed attempt at this feature. Tesseract's LSTM "fast" traineddata models
 * support both languages in a single combined initialization ("eng+ara").
 *
 * Lifecycle: call [initialize] once (it copies tessdata out of assets on first run only),
 * then [recognize] per capture, and always [release] when the caller is done with a session.
 * Every [recognize] call internally starts a fresh [TessBaseAPI] image, but the underlying
 * native engine instance is reused across calls within one [initialize]/[release] session —
 * do not call [recognize] concurrently from multiple threads.
 */
class TesseractOcrEngine(private val appContext: Context) {

    private var tessBaseApi: TessBaseAPI? = null
    private var initialized = false

    companion object {
        private const val TAG = "TesseractOcrEngine"
    }

    /**
     * Copies bundled tessdata assets to internal storage (only if not already present/stale)
     * and initializes the native Tesseract engine with combined "eng+ara" language data.
     * Returns true on success. Safe to call again after [release].
     */
    fun initialize(): Boolean {
        if (initialized && tessBaseApi != null) return true

        return try {
            val dataDir = ensureTessdataOnDisk()
            val api = TessBaseAPI()
            val ok = api.init(dataDir.absolutePath, OcrConstants.TESSERACT_LANGUAGE)
            if (!ok) {
                Log.e(TAG, "TessBaseAPI.init() returned false for path=${dataDir.absolutePath}")
                api.recycle()
                return false
            }
            api.pageSegMode = OcrConstants.PSM_SINGLE_BLOCK_INDEX
            tessBaseApi = api
            initialized = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Tesseract engine", e)
            false
        }
    }

    /**
     * Runs OCR on [bitmap] (already expected to be preprocessed by [ImagePreprocessor])
     * and returns the recognized text, trimmed. Returns null if the engine isn't initialized
     * or recognition failed.
     */
    fun recognize(bitmap: Bitmap): String? {
        val api = tessBaseApi ?: return null
        return try {
            api.setImage(bitmap)
            val text = api.utF8Text
            api.clear() // release the per-image native buffers, keep the engine/language data loaded
            text?.trim()
        } catch (e: Exception) {
            Log.e(TAG, "OCR recognition failed", e)
            null
        }
    }

    /**
     * Releases native Tesseract resources. Must be called when the engine is no longer
     * needed (e.g. after a scan completes) to avoid native memory leaks — TessBaseAPI holds
     * native heap allocations that are NOT cleaned up by the JVM garbage collector.
     */
    fun release() {
        try {
            tessBaseApi?.end()
            tessBaseApi?.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing Tesseract engine", e)
        } finally {
            tessBaseApi = null
            initialized = false
        }
    }

    /**
     * Copies assets/tessdata/*.traineddata into <filesDir>/tesseract/tessdata on first run.
     * Subsequent calls are cheap no-ops if the target files already exist with non-zero size,
     * since TessBaseAPI cannot read directly out of the APK's compressed assets.
     * Returns the *parent* directory (<filesDir>/tesseract), which is what TessBaseAPI.init()
     * expects — it appends "/tessdata" itself when locating language files.
     */
    private fun ensureTessdataOnDisk(): File {
        val rootDir = File(appContext.filesDir, OcrConstants.TESSERACT_DATA_DIR_NAME)
        val tessdataDir = File(rootDir, "tessdata")
        if (!tessdataDir.exists()) {
            tessdataDir.mkdirs()
        }

        val assetManager = appContext.assets
        val assetFiles = assetManager.list(OcrConstants.TESSDATA_ASSET_DIR).orEmpty()

        for (fileName in assetFiles) {
            if (!fileName.endsWith(".traineddata")) continue
            val outFile = File(tessdataDir, fileName)
            if (outFile.exists() && outFile.length() > 0L) continue // already copied

            var input: InputStream? = null
            var output: FileOutputStream? = null
            try {
                input = assetManager.open("${OcrConstants.TESSDATA_ASSET_DIR}/$fileName")
                output = FileOutputStream(outFile)
                input.copyTo(output)
            } finally {
                input?.close()
                output?.close()
            }
        }

        return rootDir
    }
}
