package com.example

import android.app.Application
import com.example.ocr.OcrManager

/**
 * Application subclass for Orbit.
 *
 * Sole purpose right now: initialize the [OcrManager] singleton with the application
 * context as early as possible so that the very first "Scan Screen" tap doesn't have
 * to pay the asset-copy + Tesseract init cost on the UI thread.
 *
 * Registered in AndroidManifest.xml via `android:name=".OrbitApp"` on `<application>`.
 */
class OrbitApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Hand the application context to OcrManager so it can copy traineddata files
        // from assets to filesDir without leaking an Activity context into the singleton.
        OcrManager.setApplicationContext(this)
    }
}
