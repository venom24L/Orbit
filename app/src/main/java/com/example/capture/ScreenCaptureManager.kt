package com.example.capture

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Singleton that owns the MediaProjection session for the Vault "Scan Screen" OCR feature.
 *
 * Lifecycle (CRITICAL):
 *   - The MediaProjection token is requested ONCE per app session (not on every scan),
 *     via [requestProjection]. The resulting Activity-result Intent is forwarded to
 *     [createProjection] which builds a live `MediaProjection`.
 *   - The projection is reused across multiple scans. It is NEVER persisted to disk —
 *     it lives only in this in-memory singleton.
 *   - On service teardown the projection MUST be stopped via [stopProjection] to
 *     release native resources.
 *
 * Capture flow (per scan):
 *   - [captureRegion] creates a short-lived ImageReader at full-screen size,
 *     attaches it to a fresh VirtualDisplay, waits for the first frame, copies the
 *     pixels into a Bitmap, crops the requested [Rect] out of that full-screen bitmap,
 *     releases the Image / ImageReader / VirtualDisplay, and returns the cropped region.
 *   - The returned Bitmap is ARGB_8888 and MUST be recycled by the caller after OCR.
 *   - NO bitmap is ever written to disk or to the media gallery — fully in-memory.
 *
 * Threading:
 *   - ImageReader callbacks arrive on a dedicated HandlerThread so we don't block the
 *     main thread or the service's background coroutine scope.
 *   - The suspend function is safe to call from Dispatchers.IO.
 */
object ScreenCaptureManager {

    private const val TAG = "ScreenCaptureManager"

    /**
     * Max time to wait for the first VirtualDisplay frame before giving up.
     * Generous because MediaProjection setup can take a few hundred ms on cold start.
     */
    private const val FIRST_FRAME_TIMEOUT_MS = 4_000L

    /** Desired virtual display density. Doesn't matter much since we crop from full frame. */
    private const val VIRTUAL_DISPLAY_DENSITY = 1

    /** Virtual display name shown in system debug logs only. */
    private const val VIRTUAL_DISPLAY_NAME = "orbit_ocr_capture"

    @Volatile
    private var mediaProjection: MediaProjection? = null

    /** Background thread for ImageReader callbacks. Created lazily, shut down on stopProjection. */
    private var callbackThread: HandlerThread? = null
    private var callbackHandler: Handler? = null

    /** True iff the user has granted the MediaProjection consent dialog this session. */
    fun hasActiveProjection(): Boolean = mediaProjection != null

    /**
     * Launch the system MediaProjection consent dialog. MUST be called from an Activity
     * (uses Activity Result API; caller supplies the launcher).
     *
     * The caller is responsible for forwarding the resulting `resultCode + Intent`
     * back to [createProjection].
     */
    fun createScreenCaptureIntent(context: Context): Intent {
        val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        return mpm.createScreenCaptureIntent()
    }

    /**
     * Build (or rebuild) the [MediaProjection] from an Activity-result.
     *
     * Per Android 14+ (API 34+) rules:
     *   - This MUST be called from a foreground service that has already called
     *     `startForeground(..., FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)` within
     *     10 seconds of the user granting consent. The caller (FloatingLauncherService)
     *     is responsible for that ordering — this method does not enforce it.
     *   - On older API levels the foreground-service requirement does not apply.
     *
     * Safe to call multiple times: any previous projection is stopped before creating
     * a new one, so we don't leak VirtualDisplays.
     */
    @SuppressLint("FlagNewApi")
    fun createProjection(context: Context, resultCode: Int, data: Intent) {
        if (resultCode != Activity.RESULT_OK) {
            Log.w(TAG, "createProjection called with resultCode=$resultCode (not OK); ignoring")
            return
        }
        stopProjection()
        val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        // Use the API-21+ signature that takes resultCode + data Intent. On Android 14+
        // this is the only legal way to obtain a projection from a foreground service.
        mediaProjection = mpm.getMediaProjection(resultCode, data)
        Log.d(TAG, "MediaProjection created: $mediaProjection")
    }

    /**
     * Captures the requested screen [region] and returns it as a Bitmap.
     *
     * Coordinates: [region] is in raw screen pixels in the CURRENT display orientation.
     * The caller (ScreenCaptureOverlay) is responsible for producing LTR coordinates —
     * we never mirror anything here regardless of the app's UI layout direction.
     *
     * Returns null on any failure (setup error, first-frame timeout, empty crop, etc.).
     * The returned Bitmap is ARGB_8888 and MUST be recycled by the caller.
     */
    suspend fun captureRegion(context: Context, region: Rect): Bitmap? = withContext(Dispatchers.IO) {
        val projection = mediaProjection
            ?: run {
                Log.e(TAG, "captureRegion called but no MediaProjection is active; call createProjection first")
                return@withContext null
            }

        // Resolve full-screen pixel dimensions in the current orientation.
        val (screenW, screenH) = currentScreenSizePx(context)
        if (screenW <= 0 || screenH <= 0) {
            Log.e(TAG, "Invalid screen size: ${screenW}x${screenH}")
            return@withContext null
        }

        // Clamp the requested region to the actual screen bounds (defensive).
        val clamped = Rect(
            region.left.coerceIn(0, screenW),
            region.top.coerceIn(0, screenH),
            region.right.coerceIn(0, screenW),
            region.bottom.coerceIn(0, screenH)
        )
        if (clamped.width() < 2 || clamped.height() < 2) {
            Log.e(TAG, "Capture region too small after clamping: $clamped")
            return@withContext null
        }

        ensureCallbackThread()
        val handler = callbackHandler ?: run {
            Log.e(TAG, "callbackHandler is null; cannot set up ImageReader")
            return@withContext null
        }

        // ImageReader at full screen size. Format RGBA_8888 = 4 bytes/pixel, supported on all APIs.
        // Using maxImages=2: we only need 1 frame, the second slot prevents the producer from
        // stalling if we're a tick slow releasing the first.
        val imageReader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
        var virtualDisplay: VirtualDisplay? = null
        var capturedImage: Image? = null

        try {
            // Suspend until we receive the first frame (or time out).
            val firstImage: Image? = withTimeoutOrNull(FIRST_FRAME_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    val listener = ImageReader.OnImageAvailableListener { reader ->
                        try {
                            val img = reader.acquireLatestImage()
                            if (img != null && cont.isActive) {
                                cont.resume(img)
                            } else if (img != null) {
                                img.close()
                            }
                        } catch (t: Throwable) {
                            Log.e(TAG, "OnImageAvailableListener threw", t)
                        }
                    }
                    imageReader.setOnImageAvailableListener(listener, handler)
                    virtualDisplay = projection.createVirtualDisplay(
                        VIRTUAL_DISPLAY_NAME,
                        screenW, screenH, VIRTUAL_DISPLAY_DENSITY,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        imageReader.surface, null, handler
                    )
                    if (virtualDisplay == null) {
                        Log.e(TAG, "createVirtualDisplay returned null")
                        if (cont.isActive) cont.resume(null)
                    }
                    cont.invokeOnCancellation {
                        try { imageReader.setOnImageAvailableListener(null, null) } catch (_: Throwable) {}
                    }
                }
            }
            capturedImage = firstImage
            if (capturedImage == null) {
                Log.e(TAG, "Timed out waiting for first VirtualDisplay frame")
                return@withContext null
            }

            // Copy the Image's pixel buffer into a Bitmap. RGBA_8888 Image plane has
            // rowStride that may be padded past width*4 — respect it.
            val fullBitmap = imageToBitmap(capturedImage, screenW, screenH) ?: run {
                Log.e(TAG, "Failed to convert Image to Bitmap")
                return@withContext null
            }

            // Crop the requested region out of the full-screen bitmap.
            val cropped = Bitmap.createBitmap(
                fullBitmap,
                clamped.left,
                clamped.top,
                clamped.width(),
                clamped.height()
            )
            // We don't need the full bitmap anymore — recycle it to free memory.
            if (cropped !== fullBitmap) fullBitmap.recycle()
            Log.d(TAG, "Captured region ${clamped.width()}x${clamped.height()} from screen ${screenW}x${screenH}")
            cropped
        } catch (t: Throwable) {
            Log.e(TAG, "captureRegion failed", t)
            null
        } finally {
            try { capturedImage?.close() } catch (_: Throwable) {}
            try { virtualDisplay?.release() } catch (_: Throwable) {}
            try {
                // ImageReader.close() requires API 19+ (we're minSdk 26 so fine).
                imageReader.close()
            } catch (_: Throwable) {}
        }
    }

    /**
     * Stops the current MediaProjection and tears down callback threads.
     * Safe to call multiple times. After this, a new consent dialog must be granted
     * before the next capture.
     */
    fun stopProjection() {
        try { mediaProjection?.stop() } catch (t: Throwable) {
            Log.w(TAG, "MediaProjection.stop() threw", t)
        }
        mediaProjection = null
        try { callbackThread?.quitSafely() } catch (_: Throwable) {}
        callbackThread = null
        callbackHandler = null
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun ensureCallbackThread() {
        if (callbackThread != null) return
        synchronized(this) {
            if (callbackThread != null) return
            val t = HandlerThread("orbit-ocr-image-reader").apply { start() }
            callbackThread = t
            callbackHandler = Handler(t.looper)
        }
    }

    /** Returns the current screen size in raw pixels, accounting for the current orientation. */
    private fun currentScreenSizePx(context: Context): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        // On API 30+ use the new display API; fall back to the deprecated one on older devices.
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        return Pair(metrics.widthPixels, metrics.heightPixels)
    }

    /** Converts an RGBA_8888 [Image] to a Bitmap, respecting rowStride padding. */
    private fun imageToBitmap(image: Image, width: Int, height: Int): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride  // should be 4 for RGBA_8888
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width

        val bitmap = Bitmap.createBitmap(
            width + rowPadding / pixelStride,
            height,
            Bitmap.Config.ARGB_8888
        )
        buffer.rewind()
        bitmap.copyPixelsFromBuffer(buffer)

        // If there was row padding, crop the actual width out of the padded bitmap.
        return if (rowPadding == 0) {
            bitmap
        } else {
            Bitmap.createBitmap(bitmap, 0, 0, width, height).also {
                if (it !== bitmap) bitmap.recycle()
            }
        }
    }
}
