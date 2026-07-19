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
            
            // Get expected size directly from the asset file stream
            val expectedSize = try {
                context.assets.open("tessdata/$lang.traineddata").use { input ->
                    input.available().toLong()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get asset size for $lang.traineddata", e)
                0L
            }

            Log.d(TAG, "Verifying $lang.traineddata. Expected size: $expectedSize, Current local size: ${if (destFile.exists()) destFile.length() else -1L}")

            // If file does not exist or has incorrect size, re-copy it
            if (!destFile.exists() || destFile.length() != expectedSize || expectedSize == 0L) {
                Log.d(TAG, "Copying or replacing $lang.traineddata from assets...")
                if (destFile.exists()) {
                    val deleted = destFile.delete()
                    Log.d(TAG, "Deleted old/mismatched file: $deleted")
                }
                try {
                    context.assets.open("tessdata/$lang.traineddata").use { input ->
                        FileOutputStream(destFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    Log.d(TAG, "Successfully copied $lang.traineddata. New size: ${destFile.length()}")
                } catch (e: Exception) {
                    Log.e(TAG, "Error copying $lang.traineddata from assets", e)
                    success = false
                }
            } else {
                Log.d(TAG, "$lang.traineddata is valid and already exists on internal storage")
            }
        }
        success
    }

    /**
     * Returns the path to the directory containing the 'tessdata' folder.
     * Tesseract expects the parent path (i.e., context.filesDir.absolutePath),
     * and it will automatically look for files under <path>/tessdata/.
     * We append a trailing slash (File.separator) to ensure absolute safety.
     */
    fun getTessDataPath(context: Context): String {
        val path = context.filesDir.absolutePath
        return if (path.endsWith(File.separator)) path else path + File.separator
    }
}
