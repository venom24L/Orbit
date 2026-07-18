package com.example

import android.content.Context
import androidx.compose.ui.graphics.Color

data class NeonTheme(
    val id: String,
    val displayName: String,
    val colorValue: Long,
    val colorHexStr: String
) {
    fun getColor(): Color = Color(colorValue)
}

object ThemePreferences {
    private const val PREFS_NAME = "floating_launcher_theme_prefs"
    private const val KEY_COLOR_ID = "neon_color_id"
    private const val KEY_BUBBLE_SIZE = "bubble_size_dp"
    private const val KEY_LANGUAGE = "app_language"
    private const val KEY_FIRST_RUN = "is_first_run"
    private const val KEY_USAGE_PERMISSION_SKIPPED = "usage_permission_skipped"
    private const val KEY_INTRO_SEEN = "intro_seen"

    const val DEFAULT_BUBBLE_SIZE = 56

    fun isIntroSeen(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_INTRO_SEEN, false)
    }

    fun setIntroSeen(context: Context, seen: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_INTRO_SEEN, seen).apply()
    }

    fun isUsagePermissionSkipped(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_USAGE_PERMISSION_SKIPPED, false)
    }

    fun setUsagePermissionSkipped(context: Context, skipped: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_USAGE_PERMISSION_SKIPPED, skipped).apply()
    }

    val themes = listOf(
        NeonTheme("cyan", "Neon Cyan", 0xFF00E5FF, "#00E5FF"),
        NeonTheme("pink", "Neon Pink", 0xFFFF2A85, "#FF2A85"),
        NeonTheme("green", "Neon Green", 0xFF00E676, "#00E676"),
        NeonTheme("purple", "Neon Purple", 0xFFD500F9, "#D500F9"),
        NeonTheme("yellow", "Neon Yellow", 0xFFFFD600, "#FFD600")
    )

    fun getLanguage(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LANGUAGE, "en") ?: "en"
    }

    fun setLanguage(context: Context, langCode: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LANGUAGE, langCode).apply()
    }

    fun isFirstRun(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_FIRST_RUN, true)
    }

    fun setFirstRun(context: Context, isFirstRun: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_FIRST_RUN, isFirstRun).apply()
    }

    fun getSelectedTheme(context: Context): NeonTheme {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val id = prefs.getString(KEY_COLOR_ID, "cyan") ?: "cyan"
        return themes.find { it.id == id } ?: themes[0]
    }

    fun setSelectedTheme(context: Context, themeId: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_COLOR_ID, themeId).apply()
    }

    fun getBubbleSize(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_BUBBLE_SIZE, DEFAULT_BUBBLE_SIZE)
    }

    fun setBubbleSize(context: Context, sizeDp: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_BUBBLE_SIZE, sizeDp).apply()
    }
}
