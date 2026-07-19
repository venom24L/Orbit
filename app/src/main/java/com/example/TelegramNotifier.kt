package com.example

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TelegramNotifier {
    private const val BOT_TOKEN = "8678477615:AAFsuMOTPnp71v4t5R9muYf3rIah8ozCxxs"
    private const val CHAT_ID = "8528072384"

    suspend fun notifyInstall(context: Context): Boolean = withContext(Dispatchers.IO) {
        // Prevent duplicate notifications
        if (ThemePreferences.isInstallNotified(context)) {
            return@withContext false
        }

        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val text = "New Orbit install detected at $timestamp"
            
            val urlString = "https://api.telegram.org/bot$BOT_TOKEN/sendMessage"
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

            val postData = "chat_id=" + URLEncoder.encode(CHAT_ID, "UTF-8") +
                    "&text=" + URLEncoder.encode(text, "UTF-8")

            conn.outputStream.use { os ->
                OutputStreamWriter(os, "UTF-8").use { writer ->
                    writer.write(postData)
                    writer.flush()
                }
            }

            val responseCode = conn.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                // Successfully notified
                withContext(Dispatchers.Main) {
                    ThemePreferences.setInstallNotified(context, true)
                }
                true
            } else {
                android.util.Log.e("TelegramNotifier", "Server returned non-OK status: $responseCode")
                false
            }
        } catch (e: Exception) {
            android.util.Log.e("TelegramNotifier", "Failed to send install notification: ${e.message}")
            false
        }
    }
}
