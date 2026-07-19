package com.example

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object TessDataManager {
    private const val TAG = "TessDataManager"

    /**
     * Ensures that the Tesseract trained data files (eng.traineddata and ara.traineddata)
     * are copied from the assets folder to the app's internal files/tessdata/ directory.
     * Returns true if the files are ready, false if an error occurred.
     */
    suspend fun ensureTessDataReady(context: Context): Boolean = withContext(Dispatchers.IO) {
        val tessDir = File(context.filesDir, "tessdata")
        if (!tessDir.exists()) {
            val created = tessDir.mkdirs()
            Log.d(TAG, "tessdata directory created: $created")
        }

        val languages = listOf("eng", "ara")
        var success = true

        for (lang in languages) {
            val destFile = File(tessDir, "$lang.traineddata")
            // Check if file exists and has size > 0 to ensure it wasn't a partial write
            if (!destFile.exists() || destFile.length() == 0L) {
                Log.d(TAG, "Copying $lang.traineddata from assets...")
                try {
                    context.assets.open("tessdata/$lang.traineddata").use { input ->
                        FileOutputStream(destFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    Log.d(TAG, "Successfully copied $lang.traineddata")
                } catch (e: Exception) {
                    Log.e(TAG, "Error copying $lang.traineddata from assets", e)
                    success = false
                }
            } else {
                Log.d(TAG, "$lang.traineddata already exists on internal storage")
            }
        }
        success
    }

    /**
     * Returns the path to the directory containing the 'tessdata' folder.
     * Tesseract expects the parent path (i.e., context.filesDir.absolutePath),
     * and it will automatically look for files under <path>/tessdata/.
     */
    fun getTessDataPath(context: Context): String {
        return context.filesDir.absolutePath
    }
}
