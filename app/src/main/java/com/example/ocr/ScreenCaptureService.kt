package com.example.ocr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer

/**
 * Foreground service that owns a single MediaProjection capture session.
 *
 * Design notes:
 * - The captured [Bitmap] never touches disk or the gallery: it is produced entirely from an
 *   in-memory [ImageReader] buffer and handed back via [OcrCaptureCallback], then discarded.
 * - The Android-mandated "screen is being captured" notification is satisfied by this service's
 *   own foreground notification (separate channel from the persistent bubble notification so
 *   the two coexist cleanly without one clobbering the other).
 * - MediaProjection permission (the (resultCode, Intent) pair from the system picker) is
 *   supplied by [OcrSessionManager], which caches it for the app/service session so the user
 *   is only prompted once rather than on every scan.
 */
class ScreenCaptureService : Service() {

    interface OcrCaptureCallback {
        fun onFrameCaptured(bitmap: Bitmap)
        fun onCaptureFailed(reason: String)
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val binder = LocalBinder()

    inner class LocalBinder : android.os.Binder() {
        fun getService(): ScreenCaptureService = this@ScreenCaptureService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                OcrConstants.OCR_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(OcrConstants.OCR_NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    /**
     * Captures the given [region] (in screen pixel coordinates, LTR-space) as a cropped,
     * in-memory bitmap using the cached MediaProjection grant. Must be called after this
     * service is bound and started. Results are delivered via [callback] on the main thread.
     */
    fun captureRegion(region: Rect, callback: OcrCaptureCallback) {
        val projectionData = OcrSessionManager.grantedProjectionData
        if (projectionData == null) {
            callback.onCaptureFailed("No MediaProjection permission cached for this session")
            return
        }

        serviceScope.launch {
            try {
                val bitmap = withTimeoutOrNull(OcrConstants.CAPTURE_TIMEOUT_MS) {
                    performCapture(projectionData, region)
                }
                if (bitmap != null) {
                    callback.onFrameCaptured(bitmap)
                } else {
                    callback.onCaptureFailed("Capture timed out")
                }
            } catch (e: Exception) {
                callback.onCaptureFailed(e.message ?: "Unknown capture error")
            } finally {
                tearDownCaptureSession()
            }
        }
    }

    private suspend fun performCapture(
        projectionData: OcrSessionManager.ProjectionData,
        region: Rect
    ): Bitmap {
        val deferred = CompletableDeferred<Bitmap>()

        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionManager.getMediaProjection(
            projectionData.resultCode,
            projectionData.data
        )
        mediaProjection = projection

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        handlerThread = HandlerThread("OcrCaptureThread").also { it.start() }
        handler = Handler(handlerThread!!.looper)

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val buffer: ByteBuffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width

                val fullBitmap = Bitmap.createBitmap(
                    width + rowPadding / pixelStride,
                    height,
                    Bitmap.Config.ARGB_8888
                )
                fullBitmap.copyPixelsFromBuffer(buffer)

                val safeRegion = Rect(
                    region.left.coerceIn(0, width),
                    region.top.coerceIn(0, height),
                    region.right.coerceIn(0, width),
                    region.bottom.coerceIn(0, height)
                )

                val cropped = if (safeRegion.width() > 0 && safeRegion.height() > 0) {
                    Bitmap.createBitmap(
                        fullBitmap,
                        safeRegion.left,
                        safeRegion.top,
                        safeRegion.width(),
                        safeRegion.height()
                    )
                } else {
                    fullBitmap
                }

                if (cropped !== fullBitmap) fullBitmap.recycle()

                if (!deferred.isCompleted) {
                    deferred.complete(cropped)
                }
            } finally {
                image.close()
            }
        }, handler)

        virtualDisplay = projection.createVirtualDisplay(
            "OrbitOcrCapture",
            width,
            height,
            density,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler
        )

        kotlinx.coroutines.delay(OcrConstants.CAPTURE_WARMUP_DELAY_MS)

        return deferred.await()
    }

    private fun tearDownCaptureSession() {
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        try {
            imageReader?.close()
        } catch (_: Exception) {
        }
        try {
            mediaProjection?.stop()
        } catch (_: Exception) {
        }
        try {
            handlerThread?.quitSafely()
        } catch (_: Exception) {
        }
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
        handlerThread = null
        handler = null
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                OcrConstants.OCR_NOTIFICATION_CHANNEL_ID,
                "Orbit Screen Scan",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shown while Orbit is capturing a screen region for text extraction."
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, OcrConstants.OCR_NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Orbit is scanning your screen")
            .setContentText("Extracting text from the selected region")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        tearDownCaptureSession()
    }
}
