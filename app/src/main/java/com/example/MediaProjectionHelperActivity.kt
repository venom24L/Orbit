package com.example

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast

class MediaProjectionHelperActivity : Activity() {

    private val REQUEST_CODE_MEDIA_PROJECTION = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            return
        }
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
            startActivityForResult(captureIntent, REQUEST_CODE_MEDIA_PROJECTION)
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to initiate screen capture: ${e.message}", Toast.LENGTH_SHORT).show()
            FloatingLauncherService.onProjectionPermissionDenied()
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_MEDIA_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                try {
                    // Promote FGS type to mediaProjection BEFORE calling getMediaProjection (Required on SDK >= 34)
                    FloatingLauncherService.promoteToMediaProjection()
                    
                    val projection = mediaProjectionManager.getMediaProjection(resultCode, data)
                    if (projection != null) {
                        // Register callback to clear the cached projection reference as soon as the session stops/expires
                        projection.registerCallback(object : android.media.projection.MediaProjection.Callback() {
                            override fun onStop() {
                                super.onStop()
                                if (FloatingLauncherService.mediaProjection == projection) {
                                    FloatingLauncherService.mediaProjection = null
                                }
                            }
                        }, android.os.Handler(android.os.Looper.getMainLooper()))
                        
                        FloatingLauncherService.mediaProjection = projection
                        FloatingLauncherService.onProjectionPermissionGranted()
                    } else {
                        throw Exception("MediaProjection is null")
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "Failed to get MediaProjection: ${e.message}", Toast.LENGTH_SHORT).show()
                    // Revert FGS type on failure
                    FloatingLauncherService.demoteFromMediaProjection()
                    FloatingLauncherService.onProjectionPermissionDenied()
                }
            } else {
                Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
                FloatingLauncherService.demoteFromMediaProjection()
                FloatingLauncherService.onProjectionPermissionDenied()
            }
        }
        finish()
    }
}
