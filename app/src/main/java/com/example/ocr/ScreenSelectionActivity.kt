package com.example.ocr

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.graphics.Color
import com.example.FloatingLauncherService
import com.example.OverlayActivity
import com.example.data.OrbitDatabase
import com.example.data.VaultEntry
import com.example.ui.theme.MyApplicationTheme
import com.example.ThemePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Orchestrates the full "Scan Screen" sequence:
 * 1. Hide Orbit's own overlay UI + bubble (so the captured frame shows the app underneath).
 * 2. Wait [OcrConstants.UI_DISMISS_RELIABILITY_DELAY_MS] for the compositor to catch up.
 * 3. Show the LTR-forced selection overlay for the user to draw/adjust a rectangle.
 * 4. Request MediaProjection consent (only if not already cached this session) and capture
 *    just the selected region, entirely in-memory.
 * 5. Preprocess + run Tesseract OCR (eng+ara) on the cropped bitmap.
 * 6. Save the result into the Vault Room database with source = "OCR".
 * 7. Restore the bubble and reopen the app directly on the Vault tab.
 */
class ScreenSelectionActivity : ComponentActivity() {

    private var captureService: ScreenCaptureService? = null
    private var serviceBound = false
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            captureService = (binder as? ScreenCaptureService.LocalBinder)?.getService()
            serviceBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            captureService = null
            serviceBound = false
        }
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            OcrSessionManager.cacheGrant(result.resultCode, result.data!!)
            proceedWithCapture()
        } else {
            Toast.makeText(this, "Screen capture permission was denied", Toast.LENGTH_SHORT).show()
            finishAndRestoreBubble()
        }
    }

    private var pendingRegion: Rect? = null
    private var statusState = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Bind the capture service up front so it's ready by the time the user confirms a region.
        bindService(
            Intent(this, ScreenCaptureService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )

        val activeTheme = ThemePreferences.getSelectedTheme(this)

        setContent {
            var showSelection by remember { mutableStateOf(true) }
            val status by statusState

            MyApplicationTheme(accentColor = activeTheme.getColor()) {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (showSelection && status == null) {
                        ScreenSelectionOverlay(
                            accentColor = activeTheme.getColor(),
                            onConfirm = { rect ->
                                showSelection = false
                                onRegionConfirmed(rect)
                            },
                            onCancel = {
                                finishAndRestoreBubble()
                            }
                        )
                    }
                    if (status != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .then(Modifier),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = activeTheme.getColor())
                        }
                    }
                }
            }
        }

        // Step 1 + 2: hide our own UI immediately on entering this activity, before the user
        // even finishes drawing the rectangle, so nothing of Orbit's chrome lingers behind
        // translucent layers by the time capture happens.
        FloatingLauncherService.instance?.hideBubbleForOcrCapture()
    }

    private fun onRegionConfirmed(composeRect: ComposeRect) {
        pendingRegion = Rect(
            composeRect.left.toInt(),
            composeRect.top.toInt(),
            composeRect.right.toInt(),
            composeRect.bottom.toInt()
        )
        statusState.value = "Preparing capture\u2026"

        activityScope.launch {
            // Reliability delay: give the window manager/compositor time to finish removing
            // Orbit's overlay Compose views (dismissed in onCreate) before we grab the frame.
            delay(OcrConstants.UI_DISMISS_RELIABILITY_DELAY_MS)

            if (OcrSessionManager.hasCachedGrant()) {
                proceedWithCapture()
            } else {
                val projectionManager =
                    getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
            }
        }
    }

    private fun proceedWithCapture() {
        val region = pendingRegion
        val service = captureService
        if (region == null || service == null) {
            Toast.makeText(this, "Capture service not ready", Toast.LENGTH_SHORT).show()
            finishAndRestoreBubble()
            return
        }

        statusState.value = "Capturing\u2026"

        service.captureRegion(region, object : ScreenCaptureService.OcrCaptureCallback {
            override fun onFrameCaptured(bitmap: Bitmap) {
                runOcrAndSave(bitmap)
            }

            override fun onCaptureFailed(reason: String) {
                Toast.makeText(this@ScreenSelectionActivity, "Capture failed: $reason", Toast.LENGTH_SHORT).show()
                finishAndRestoreBubble()
            }
        })
    }

    private fun runOcrAndSave(bitmap: Bitmap) {
        statusState.value = "Reading text\u2026"
        activityScope.launch {
            val recognizedText = withContext(Dispatchers.Default) {
                val engine = TesseractOcrEngine(applicationContext)
                try {
                    val preprocessed = ImagePreprocessor.preprocess(bitmap)
                    val ok = engine.initialize()
                    if (!ok) return@withContext null
                    val text = engine.recognize(preprocessed)
                    preprocessed.recycle()
                    text
                } finally {
                    // Always release native resources, success or failure.
                    engine.release()
                }
            }
            bitmap.recycle()

            if (recognizedText.isNullOrBlank()) {
                Toast.makeText(this@ScreenSelectionActivity, "No text detected in that region", Toast.LENGTH_SHORT).show()
                finishAndRestoreBubble()
                return@launch
            }

            withContext(Dispatchers.IO) {
                val database = OrbitDatabase.getDatabase(applicationContext)
                database.vaultDao().insert(
                    VaultEntry(
                        content = recognizedText,
                        source = OcrConstants.SOURCE_OCR
                    )
                )
            }

            finishAndRestoreBubble(openVault = true)
        }
    }

    private fun finishAndRestoreBubble(openVault: Boolean = false) {
        FloatingLauncherService.instance?.showBubbleForOcrRestore()

        if (openVault) {
            val launchIntent = Intent(this, OverlayActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("launch_tab", "vault")
            }
            startActivity(launchIntent)
        }

        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            try {
                unbindService(serviceConnection)
            } catch (_: Exception) {
            }
            serviceBound = false
        }
        activityScope.cancel()
    }
}
