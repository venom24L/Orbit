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
            
            // Use known exact sizes of the assets to avoid unreliable .available() estimates from compressed streams
            val expectedSize = when (lang) {
                "eng" -> 4113088L
                "ara" -> 1432056L
                else -> 0L
            }

            Log.d(TAG, "Verifying $lang.traineddata. Expected size: $expectedSize, Current local size: ${if (destFile.exists()) destFile.length() else -1L}")

            // If file does not exist or has incorrect size, re-copy it
            if (!destFile.exists() || destFile.length() != expectedSize || expectedSize == 0L) {
                Log.d(TAG, "Copying or replacing $lang.traineddata from assets...")
                
                // One-time cleanup of any existing file to start fresh
                if (destFile.exists()) {
                    val deleted = destFile.delete()
                    Log.d(TAG, "Deleted old/mismatched file: $deleted")
                }

                val tempFile = File(tessDir, "$lang.traineddata.tmp")
                if (tempFile.exists()) {
                    tempFile.delete()
                }

                try {
                    var totalBytesRead = 0L
                    context.assets.open("tessdata/$lang.traineddata").use { input ->
                        FileOutputStream(tempFile).use { output ->
                            val buffer = ByteArray(1024 * 64) // 64KB buffer
                            var bytesRead = input.read(buffer)
                            while (bytesRead != -1) {
                                output.write(buffer, 0, bytesRead)
                                totalBytesRead += bytesRead
                                bytesRead = input.read(buffer)
                            }
                            output.flush()
                        }
                    }

                    Log.d(TAG, "Finished copying to temp file. Bytes read: $totalBytesRead, Temp file size: ${tempFile.length()}")

                    if (totalBytesRead == 0L || tempFile.length() == 0L) {
                        throw Exception("Copied 0 bytes for $lang.traineddata!")
                    }

                    if (expectedSize > 0L && tempFile.length() != expectedSize) {
                        throw Exception("Copied size mismatch for $lang.traineddata! Expected: $expectedSize, Got: ${tempFile.length()}")
                    }

                    // Rename temp file to dest file
                    val renamed = tempFile.renameTo(destFile)
                    if (!renamed) {
                        throw Exception("Failed to rename temporary file to ${destFile.name}")
                    }
                    Log.d(TAG, "Successfully copied and verified $lang.traineddata. Final size: ${destFile.length()}")
                } catch (e: Exception) {
                    Log.e(TAG, "Error copying $lang.traineddata from assets", e)
                    if (tempFile.exists()) {
                        tempFile.delete()
                    }
                    if (destFile.exists()) {
                        destFile.delete()
                    }
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
