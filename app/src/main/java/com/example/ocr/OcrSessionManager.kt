package com.example.ocr

import android.content.Intent

/**
 * Caches the MediaProjection permission grant (resultCode + data Intent returned by the
 * system's screen-capture consent dialog) for the lifetime of the app/service process, so the
 * user is only prompted once per session rather than being re-asked on every single scan.
 *
 * This is intentionally a process-lifetime, in-memory cache (not persisted to disk): a
 * MediaProjection grant token is one-shot-per-process by Android's design once its
 * MediaProjection.Callback fires a stop, so we clear it via [clear] whenever the projection
 * is stopped/revoked and the next scan will naturally re-request consent.
 */
object OcrSessionManager {

    data class ProjectionData(val resultCode: Int, val data: Intent)

    @Volatile
    var grantedProjectionData: ProjectionData? = null
        private set

    fun cacheGrant(resultCode: Int, data: Intent) {
        grantedProjectionData = ProjectionData(resultCode, data)
    }

    fun hasCachedGrant(): Boolean = grantedProjectionData != null

    fun clear() {
        grantedProjectionData = null
    }
}
