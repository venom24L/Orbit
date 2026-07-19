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
    private val BOT_TOKEN = BuildConfig.TELEGRAM_BOT_TOKEN
    private val CHAT_ID = BuildConfig.TELEGRAM_CHAT_ID

    private fun getCountryName(context: Context): String? {
        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
            val code = tm?.networkCountryIso?.takeIf { it.isNotEmpty() }
                ?: tm?.simCountryIso?.takeIf { it.isNotEmpty() }
                ?: Locale.getDefault().country?.takeIf { it.isNotEmpty() }

            if (code != null) {
                val locale = Locale("", code)
                val displayCountry = locale.displayCountry
                if (displayCountry.isNotEmpty()) {
                    return displayCountry
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("TelegramNotifier", "Failed to detect country: ${e.message}")
        }
        return null
    }

    suspend fun notifyInstall(context: Context): Boolean = withContext(Dispatchers.IO) {
        // Prevent duplicate notifications
        if (ThemePreferences.isInstallNotified(context)) {
            return@withContext false
        }

        try {
            val now = Date()
            val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)
            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(now)
            
            val country = getCountryName(context)
            
            val text = buildString {
                if (country != null) {
                    append("🔔 *New install from $country*\n")
                } else {
                    append("🔔 *New install*\n")
                }
                append("📅 $dateStr\n")
                append("🕒 $timeStr")
            }
            
            val urlString = "https://api.telegram.org/bot$BOT_TOKEN/sendMessage"
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

            val postData = "chat_id=" + URLEncoder.encode(CHAT_ID, "UTF-8") +
                    "&text=" + URLEncoder.encode(text, "UTF-8") +
                    "&parse_mode=" + URLEncoder.encode("Markdown", "UTF-8")

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
