package com.example

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import java.util.Calendar

data class AppInfo(
    val packageName: String,
    val activityName: String,
    val label: String,
    val usageTimeMs: Long,
    val icon: Drawable?
)

object AppCache {
    @Volatile
    private var cachedApps: List<AppInfo>? = null

    /**
     * Gets launchable apps, returning instantly if cached in RAM.
     * Thread-safe double-checked locking mechanism.
     */
    fun getApps(context: Context): List<AppInfo> {
        val currentCache = cachedApps
        if (currentCache != null) {
            return currentCache
        }
        synchronized(this) {
            var cache = cachedApps
            if (cache == null) {
                cache = loadLaunchableApps(context)
                cachedApps = cache
            }
            return cache
        }
    }

    /**
     * Forces a refresh of the cache from package manager on package alterations.
     */
    fun refreshCache(context: Context) {
        synchronized(this) {
            cachedApps = loadLaunchableApps(context)
        }
    }

    private fun loadLaunchableApps(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
        val usageStatsMap = getAppUsageStats(context)
        val ownPackage = context.packageName

        val appsList = ArrayList<AppInfo>()
        for (resolveInfo in resolveInfos) {
            val packageName = resolveInfo.activityInfo.packageName
            // Self-reading support: Allow Orbit itself to show in the launcher list
            // if (packageName == ownPackage) continue

            val activityName = resolveInfo.activityInfo.name
            val label = resolveInfo.loadLabel(pm).toString()
            val usageTime = usageStatsMap[packageName] ?: 0L
            val icon = try {
                resolveInfo.loadIcon(pm)
            } catch (e: Exception) {
                null
            }

            appsList.add(AppInfo(packageName, activityName, label, usageTime, icon))
        }
        return appsList
    }

    private fun getAppUsageStats(context: Context): Map<String, Long> {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return emptyMap()
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, -7)
        val startTime = calendar.timeInMillis
        val endTime = System.currentTimeMillis()

        val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_BEST, startTime, endTime)
        if (stats.isNullOrEmpty()) return emptyMap()

        val map = mutableMapOf<String, Long>()
        for (stat in stats) {
            val totalTime = stat.totalTimeInForeground
            if (totalTime > 0) {
                map[stat.packageName] = (map[stat.packageName] ?: 0L) + totalTime
            }
        }
        return map
    }
}
