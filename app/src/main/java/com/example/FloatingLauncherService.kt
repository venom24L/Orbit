package com.example

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.ui.theme.MyApplicationTheme
import com.example.data.OrbitDatabase
import com.example.data.VaultEntry
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import android.media.ImageReader
import android.hardware.display.VirtualDisplay
import android.hardware.display.DisplayManager
import android.graphics.Bitmap
import android.util.DisplayMetrics
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.math.hypot

sealed class OcrModuleState {
    object Undefined : OcrModuleState()
    object Available : OcrModuleState()
    object Pending : OcrModuleState()
    data class Downloading(val progress: Int) : OcrModuleState()
    object Installing : OcrModuleState()
    object Completed : OcrModuleState()
    data class Failed(val error: String) : OcrModuleState()
}

class FloatingLauncherService : Service() {

    private var windowManager: WindowManager? = null
    private var bubbleView: FrameLayout? = null
    private var bubbleImageView: ImageView? = null
    private var isViewAdded = false
    private var snapAnimator: ValueAnimator? = null
    
    private var isBubbleHidden = false
    private var bubbleParams: WindowManager.LayoutParams? = null
    
    private var dismissZoneView: FrameLayout? = null
    private var isDismissZoneAdded = false
    
    private var springX: SpringAnimation? = null

    // Tracking screen region selection overlay
    private var selectionOverlayView: View? = null
    private var selectionOverlayLifecycleOwner: ServiceLifecycleOwner? = null

    // Background scope to preload the cache without blocking the main UI thread
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Dynamically registered receivers to avoid battery drain when screen is idle
    private var screenReceiver: BroadcastReceiver? = null
    private var packageReceiver: BroadcastReceiver? = null

    companion object {
        private const val CHANNEL_ID = "floating_launcher_channel"
        private const val NOTIFICATION_ID = 4132
        const val ACTION_UPDATE_THEME = "com.example.ACTION_UPDATE_THEME"
        const val ACTION_SHOW_BUBBLE = "com.example.ACTION_SHOW_BUBBLE"

        // Accessible from MainActivity to observe service lifecycle state in real-time
        val isServiceRunning = MutableStateFlow(false)

        val ocrModuleState = MutableStateFlow<OcrModuleState>(OcrModuleState.Undefined)

        fun downloadOcrModule(context: Context) {
            try {
                com.google.mlkit.common.sdkinternal.MlKitContext.initializeIfNeeded(context.applicationContext)
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                val moduleInstallClient = com.google.android.gms.common.moduleinstall.ModuleInstall.getClient(context.applicationContext)
                
                moduleInstallClient.areModulesAvailable(recognizer)
                    .addOnSuccessListener { response ->
                        if (response.areModulesAvailable()) {
                            ocrModuleState.value = OcrModuleState.Available
                        } else {
                            ocrModuleState.value = OcrModuleState.Pending
                            
                            val listener = com.google.android.gms.common.moduleinstall.InstallStatusListener { event ->
                                val progressInfo = event.progressInfo
                                val progress = if (progressInfo != null && progressInfo.totalBytesToDownload > 0) {
                                    (progressInfo.bytesDownloaded * 100 / progressInfo.totalBytesToDownload).toInt()
                                } else {
                                    0
                                }
                                
                                when (event.installState) {
                                    com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate.InstallState.STATE_PENDING -> {
                                        ocrModuleState.value = OcrModuleState.Pending
                                    }
                                    com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate.InstallState.STATE_DOWNLOADING -> {
                                        ocrModuleState.value = OcrModuleState.Downloading(progress)
                                    }
                                    com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate.InstallState.STATE_INSTALLING -> {
                                        ocrModuleState.value = OcrModuleState.Installing
                                    }
                                    com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED -> {
                                        ocrModuleState.value = OcrModuleState.Available
                                    }
                                    com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate.InstallState.STATE_FAILED,
                                    com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate.InstallState.STATE_CANCELED -> {
                                        ocrModuleState.value = OcrModuleState.Failed("Download failed. Please check internet connection.")
                                    }
                                }
                            }
                            
                            val request = com.google.android.gms.common.moduleinstall.ModuleInstallRequest.newBuilder()
                                .addApi(recognizer)
                                .setListener(listener)
                                .build()
                                
                            moduleInstallClient.installModules(request)
                                .addOnSuccessListener {
                                    android.util.Log.d("OrbitOCR", "OCR module download initiated successfully.")
                                }
                                .addOnFailureListener { e ->
                                    android.util.Log.e("OrbitOCR", "Failed to initiate OCR module installation", e)
                                    ocrModuleState.value = OcrModuleState.Failed(e.message ?: "Failed to start download")
                                }
                        }
                    }
                    .addOnFailureListener { e ->
                        android.util.Log.e("OrbitOCR", "Failed checking OCR module availability", e)
                        ocrModuleState.value = OcrModuleState.Failed(e.message ?: "Failed checking availability")
                    }
            } catch (e: Exception) {
                e.printStackTrace()
                ocrModuleState.value = OcrModuleState.Failed(e.message ?: "Unknown error")
            }
        }

        @Volatile
        var instance: FloatingLauncherService? = null

        // MediaProjection session
        var mediaProjection: android.media.projection.MediaProjection? = null

        fun promoteToMediaProjection() {
            instance?.promoteToMediaProjectionInstance()
        }

        fun demoteFromMediaProjection() {
            instance?.demoteFromMediaProjectionInstance()
        }

        // Named transition delay constant for real-device reliability
        const val POST_DISMISSAL_DELAY_MS = 150L

        private var onPermissionGrantedCallback: (() -> Unit)? = null
        private var onPermissionDeniedCallback: (() -> Unit)? = null

        fun onProjectionPermissionGranted() {
            onPermissionGrantedCallback?.invoke()
            onPermissionGrantedCallback = null
            onPermissionDeniedCallback = null
        }

        fun onProjectionPermissionDenied() {
            onPermissionDeniedCallback?.invoke()
            onPermissionGrantedCallback = null
            onPermissionDeniedCallback = null
        }

        fun startOcrCapture(context: Context) {
            val service = instance
            if (service == null) {
                Toast.makeText(context, "Orbit Service is not running", Toast.LENGTH_SHORT).show()
                return
            }

            if (mediaProjection != null) {
                // Already have permission! Show selection overlay directly after post-dismissal delay
                service.serviceScope.launch(Dispatchers.Main) {
                    delay(POST_DISMISSAL_DELAY_MS)
                    service.showSelectionOverlay()
                }
            } else {
                // Request MediaProjection permission first
                onPermissionGrantedCallback = {
                    service.serviceScope.launch(Dispatchers.Main) {
                        delay(POST_DISMISSAL_DELAY_MS)
                        service.showSelectionOverlay()
                    }
                }
                onPermissionDeniedCallback = {
                    // Reopen OverlayActivity so user isn't stuck
                    val reopenIntent = Intent(context, OverlayActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        putExtra("launch_tab", "vault")
                    }
                    context.startActivity(reopenIntent)
                }

                val intent = Intent(context, MediaProjectionHelperActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            com.google.mlkit.common.sdkinternal.MlKitContext.initializeIfNeeded(applicationContext)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        isServiceRunning.value = true
        instance = this

        registerScreenReceiver()
        registerPackageReceiver()

        // Preload in-memory cache in background to achieve instant launch
        serviceScope.launch {
            AppCache.getApps(this@FloatingLauncherService)
        }

        downloadOcrModule(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, 
                buildNotification(isBubbleHidden), 
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(isBubbleHidden))
        }

        when (intent?.action) {
            ACTION_UPDATE_THEME -> {
                updateBubbleThemeAndSize()
            }
            ACTION_SHOW_BUBBLE -> {
                showBubble()
            }
            else -> {
                if (isBubbleHidden) {
                    showBubble()
                } else {
                    addFloatingBubble()
                }
            }
        }

        return START_STICKY
    }

    private fun registerScreenReceiver() {
        if (screenReceiver != null) return
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        // Pause layout updates, hide bubble from WindowManager and stop all animations immediately
                        bubbleView?.visibility = View.GONE
                        snapAnimator?.cancel()
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        // Resume and show the bubble again when the user is active
                        bubbleView?.visibility = View.VISIBLE
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenReceiver, filter)
    }

    private fun registerPackageReceiver() {
        if (packageReceiver != null) return
        packageReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                context?.let { ctx ->
                    // Run refresh in background thread to avoid blockages
                    serviceScope.launch {
                        AppCache.refreshCache(ctx)
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        registerReceiver(packageReceiver, filter)
    }

    private fun updateBubbleThemeAndSize() {
        val context = this
        val theme = ThemePreferences.getSelectedTheme(context)
        val color = theme.colorValue.toInt()
        
        val sizeDp = ThemePreferences.getBubbleSize(context)
        val sizePx = dpToPx(sizeDp.toFloat(), context)

        bubbleView?.let { view ->
            view.layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)
            view.setBackgroundColor(Color.TRANSPARENT)
            
            // Find ImageView child and update layout params
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                if (child is ImageView) {
                    child.layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        Gravity.CENTER
                    )
                    child.setImageResource(R.drawable.ic_orbit_neon)
                }
            }
            
            bubbleParams?.let { params ->
                params.width = sizePx
                params.height = sizePx
                try {
                    windowManager?.updateViewLayout(view, params)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun showBubble() {
        if (!Settings.canDrawOverlays(this)) {
            return
        }
        if (isBubbleHidden && bubbleView != null) {
            try {
                val sizeDp = ThemePreferences.getBubbleSize(this)
                val sizePx = dpToPx(sizeDp.toFloat(), this)
                bubbleParams?.let { params ->
                    params.width = sizePx
                    params.height = sizePx
                    windowManager?.addView(bubbleView, params)
                }
                isViewAdded = true
                isBubbleHidden = false
                updateNotification(false)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun hideBubble() {
        if (isViewAdded && bubbleView != null) {
            try {
                windowManager?.removeView(bubbleView)
                isViewAdded = false
                isBubbleHidden = true
                updateNotification(true)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun createDismissZoneView() {
        if (dismissZoneView != null) return
        val context = this
        val size = dpToPx(80f, context)
        
        val view = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(size, size)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x33FF0000.toInt()) // subtle reddish glow
                setStroke(dpToPx(2f, context), 0xFFFF3366.toInt()) // neon red/pink border
            }
            
            val xText = TextView(context).apply {
                text = "✕"
                textSize = 28f
                setTextColor(0xFFFF3366.toInt())
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            addView(xText)
        }
        dismissZoneView = view
    }

    private fun showDismissZone() {
        if (!Settings.canDrawOverlays(this)) {
            return
        }
        if (isDismissZoneAdded) return
        createDismissZoneView()
        dismissZoneView?.let { view ->
            val size = dpToPx(80f, this)
            val params = WindowManager.LayoutParams(
                size,
                size,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = dpToPx(50f, this@FloatingLauncherService)
            }
            try {
                windowManager?.addView(view, params)
                isDismissZoneAdded = true
                view.scaleX = 0f
                view.scaleY = 0f
                view.animate().scaleX(1.2f).scaleY(1.2f).setDuration(180).withEndAction {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                }.start()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun hideDismissZone() {
        if (!isDismissZoneAdded) return
        dismissZoneView?.let { view ->
            try {
                view.animate().scaleX(0f).scaleY(0f).setDuration(150).withEndAction {
                    try {
                        windowManager?.removeView(view)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }.start()
            } catch (e: Exception) {
                try {
                    windowManager?.removeView(view)
                } catch (ex: Exception) {
                    ex.printStackTrace()
                }
            }
            isDismissZoneAdded = false
        }
    }

    private fun isOverlappingDismissZone(bubbleX: Int, bubbleY: Int): Boolean {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        
        val sizeDp = ThemePreferences.getBubbleSize(this)
        val sizePx = dpToPx(sizeDp.toFloat(), this)
        val bubbleCenterX = bubbleX + sizePx / 2
        val bubbleCenterY = bubbleY + sizePx / 2
        
        val zoneSize = dpToPx(80f, this)
        val zoneYMargin = dpToPx(50f, this)
        
        val zoneCenterX = screenWidth / 2
        val zoneCenterY = screenHeight - zoneYMargin - (zoneSize / 2)
        
        val distance = hypot((bubbleCenterX - zoneCenterX).toFloat(), (bubbleCenterY - zoneCenterY).toFloat())
        return distance < dpToPx(100f, this)
    }

    private fun addFloatingBubble() {
        if (!Settings.canDrawOverlays(this)) {
            return
        }
        if (bubbleView != null) return

        val context = this
        val sizeDp = ThemePreferences.getBubbleSize(context)
        val size = dpToPx(sizeDp.toFloat(), context)
        val currentTheme = ThemePreferences.getSelectedTheme(context)
        val accentColorInt = currentTheme.colorValue.toInt()

        val view = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(size, size)
            setBackgroundColor(Color.TRANSPARENT)
        }

        val imageView = ImageView(context).apply {
            setImageResource(R.drawable.ic_orbit_neon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        }
        bubbleImageView = imageView
        view.addView(imageView)
        bubbleView = view

        val params = WindowManager.LayoutParams(
            size,
            size,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 350
        }
        bubbleParams = params

        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        var initialX = 0f
        var initialY = 0f
        var initialParamsX = 0
        var initialParamsY = 0
        var isDragging = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = event.rawX
                    initialY = event.rawY
                    initialParamsX = params.x
                    initialParamsY = params.y
                    isDragging = false
                    springX?.cancel()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialX
                    val dy = event.rawY - initialY
                    if (!isDragging && hypot(dx, dy) > touchSlop) {
                        isDragging = true
                        showDismissZone()
                    }
                    if (isDragging) {
                        params.x = (initialParamsX + dx).toInt()
                        params.y = (initialParamsY + dy).toInt()
                        
                        val displayMetrics = context.resources.displayMetrics
                        val screenHeight = displayMetrics.heightPixels
                        if (params.y < 0) params.y = 0
                        if (params.y > screenHeight - size) params.y = screenHeight - size

                        try {
                            windowManager?.updateViewLayout(view, params)
                        } catch (e: Exception) {
                            // ignore
                        }

                        // Check overlapping dismiss zone
                        val isOverlapping = isOverlappingDismissZone(params.x, params.y)
                        dismissZoneView?.let { zone ->
                            val targetScale = if (isOverlapping) 1.25f else 1.0f
                            if (zone.scaleX != targetScale) {
                                zone.animate().scaleX(targetScale).scaleY(targetScale).setDuration(120).start()
                            }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDragging) {
                        hideDismissZone()
                        
                        val isOverlapping = isOverlappingDismissZone(params.x, params.y)
                        if (isOverlapping) {
                            hideBubble()
                        } else {
                            // Snap horizontally with Spring Force!
                            val displayMetrics = context.resources.displayMetrics
                            val screenWidth = displayMetrics.widthPixels
                            val centerX = params.x + size / 2
                            val targetX = if (centerX < screenWidth / 2) {
                                0
                            } else {
                                screenWidth - size
                            }

                            springX?.cancel()
                            val xHolder = FloatValueHolder(params.x.toFloat())
                            springX = SpringAnimation(xHolder).apply {
                                spring = SpringForce().apply {
                                    dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
                                    stiffness = SpringForce.STIFFNESS_LOW
                                    finalPosition = targetX.toFloat()
                                }
                                addUpdateListener { _, value, _ ->
                                    params.x = value.toInt()
                                    try {
                                        windowManager?.updateViewLayout(view, params)
                                    } catch (e: Exception) {
                                        // ignore
                                    }
                                }
                            }
                            springX?.start()
                        }
                    } else {
                        // Tapped! Open launchpad
                        val launchIntent = Intent(context, OverlayActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        }
                        context.startActivity(launchIntent)
                    }
                    true
                }
                else -> false
            }
        }

        try {
            windowManager?.addView(view, params)
            isViewAdded = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Orbit Service Notifications",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the floating quick launch bubble active over other apps."
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(isBackgroundMode: Boolean = false): Notification {
        val showIntent = Intent(this, FloatingLauncherService::class.java).apply {
            action = ACTION_SHOW_BUBBLE
        }
        val pendingIntent = PendingIntent.getService(
            this,
            1,
            showIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val title = "Orbit"
        val text = if (isBackgroundMode) {
            "Orbit is running in the background - Tap to show bubble"
        } else {
            "Orbit floating launcher is active and ready."
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_dialer)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(isBackgroundMode: Boolean) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(isBackgroundMode))
    }

    fun promoteToMediaProjectionInstance() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(isBubbleHidden),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        }
    }

    fun demoteFromMediaProjectionInstance() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(isBubbleHidden),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        }
    }

    private fun showSelectionOverlay() {
        val context = this
        val lifecycleOwner = ServiceLifecycleOwner()
        selectionOverlayLifecycleOwner = lifecycleOwner

        var composeView: ComposeView? = null
        composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val currentTheme = ThemePreferences.getSelectedTheme(context)
                MyApplicationTheme(accentColor = currentTheme.getColor()) {
                    SelectionOverlayUI(
                        accentColor = currentTheme.getColor(),
                        onCancel = {
                            removeSelectionOverlay()
                        },
                        onCapture = { left, top, right, bottom ->
                            // Temporarily hide the overlay view to get a clean screenshot of the background app
                            composeView?.visibility = View.GONE
                            
                            // Allow UI thread a frame to fully hide the overlay
                            Handler(Looper.getMainLooper()).postDelayed({
                                performScreenCapture(
                                    left = left,
                                    top = top,
                                    right = right,
                                    bottom = bottom,
                                    onSuccess = { croppedBitmap ->
                                        // Bring back or remove the overlay
                                        removeSelectionOverlay()
                                        processOcrAndSave(croppedBitmap)
                                    },
                                    onError = { error ->
                                        composeView?.visibility = View.VISIBLE
                                        Toast.makeText(context, "Capture failed: ${error.message}", Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }, 50)
                        }
                    )
                }
            }
        }
        selectionOverlayView = composeView

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        try {
            windowManager?.addView(composeView, params)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Failed to display selection overlay", Toast.LENGTH_SHORT).show()
        }
    }

    private fun removeSelectionOverlay() {
        selectionOverlayView?.let { view ->
            try {
                windowManager?.removeViewImmediate(view)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        selectionOverlayView = null
        selectionOverlayLifecycleOwner?.onDestroy()
        selectionOverlayLifecycleOwner = null
    }

    private fun performScreenCapture(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        onSuccess: (Bitmap) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        val projection = mediaProjection
        if (projection == null) {
            onError(IllegalStateException("MediaProjection is not initialized or expired"))
            return
        }

        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val densityDpi = displayMetrics.densityDpi

        val imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        var virtualDisplay: android.hardware.display.VirtualDisplay? = null
        
        val projectionCallback = object : android.media.projection.MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                try {
                    virtualDisplay?.release()
                    virtualDisplay = null
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                try {
                    imageReader.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        try {
            // Register callback BEFORE starting capture (Required on Android 14+ / API 34+)
            projection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))

            var captureDone = false
            imageReader.setOnImageAvailableListener({ reader ->
                if (captureDone) return@setOnImageAvailableListener
                captureDone = true
                
                try {
                    val image = reader.acquireLatestImage()
                    if (image != null) {
                        val planes = image.planes
                        val buffer = planes[0].buffer
                        val pixelStride = planes[0].pixelStride
                        val rowStride = planes[0].rowStride
                        val rowPadding = rowStride - pixelStride * screenWidth
                        
                        val fullBitmap = Bitmap.createBitmap(
                            screenWidth + rowPadding / pixelStride,
                            screenHeight,
                            Bitmap.Config.ARGB_8888
                        )
                        fullBitmap.copyPixelsFromBuffer(buffer)
                        image.close()

                        // Crop to screenWidth and screenHeight first to discard trailing padding
                        val cleanFullBitmap = Bitmap.createBitmap(fullBitmap, 0, 0, screenWidth, screenHeight)
                        if (cleanFullBitmap != fullBitmap) {
                            fullBitmap.recycle()
                        }

                        // Coerce bounds to screen size
                        val cropLeft = left.coerceIn(0f, screenWidth.toFloat()).toInt()
                        val cropTop = top.coerceIn(0f, screenHeight.toFloat()).toInt()
                        val cropWidth = (right - left).coerceIn(10f, (screenWidth - cropLeft).toFloat()).toInt()
                        val cropHeight = (bottom - top).coerceIn(10f, (screenHeight - cropTop).toFloat()).toInt()

                        val croppedBitmap = Bitmap.createBitmap(cleanFullBitmap, cropLeft, cropTop, cropWidth, cropHeight)
                        cleanFullBitmap.recycle()

                        onSuccess(croppedBitmap)
                    } else {
                        onError(Exception("Failed to acquire latest image frame"))
                    }
                } catch (e: Exception) {
                    onError(e)
                } finally {
                    try {
                        projection.unregisterCallback(projectionCallback)
                    } catch (ex: Exception) {
                        ex.printStackTrace()
                    }
                    virtualDisplay?.release()
                    imageReader.close()

                    // On Android 14+ (API >= 34), a MediaProjection is single-use and consumed.
                    // Stop it now so the callback nullifies FloatingLauncherService.mediaProjection,
                    // allowing the next scan to acquire a fresh valid session.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        try {
                            projection.stop()
                        } catch (ex: Exception) {
                            ex.printStackTrace()
                        }
                    }
                }
            }, Handler(Looper.getMainLooper()))

            virtualDisplay = projection.createVirtualDisplay(
                "OrbitCapture",
                screenWidth,
                screenHeight,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface,
                null,
                null
            )
        } catch (e: Exception) {
            try {
                projection.unregisterCallback(projectionCallback)
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
            virtualDisplay?.release()
            imageReader.close()

            // On Android 14+ (API >= 34), stop the projection on immediate error too
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                try {
                    projection.stop()
                } catch (ex: Exception) {
                    ex.printStackTrace()
                }
            }
            onError(e)
        }
    }

    private fun processOcrAndSave(bitmap: Bitmap, retryCount: Int = 0) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val image = InputImage.fromBitmap(bitmap, 0)

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val text = visionText.text
                bitmap.recycle() // Clean up bitmap memory immediately
                
                if (text.isNotBlank()) {
                    serviceScope.launch(Dispatchers.IO) {
                        val db = OrbitDatabase.getDatabase(this@FloatingLauncherService)
                        // Add new OCR sourced item to Room database
                        db.vaultDao().insert(VaultEntry(content = text, source = "OCR"))
                        
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@FloatingLauncherService, "Text extracted & saved to Vault!", Toast.LENGTH_SHORT).show()
                            
                            // Smoothly bring back OverlayActivity to the Vault tab!
                            val reopenIntent = Intent(this@FloatingLauncherService, OverlayActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                putExtra("launch_tab", "vault")
                            }
                            startActivity(reopenIntent)
                        }
                    }
                } else {
                    Toast.makeText(this@FloatingLauncherService, "No text detected in selection region.", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener { exception ->
                val isModuleDownloading = if (exception is com.google.mlkit.common.MlKitException) {
                    exception.errorCode == 14 || // MlKitException.UNAVAILABLE
                    exception.errorCode == com.google.mlkit.common.MlKitException.UNAVAILABLE ||
                    exception.message?.contains("download", ignoreCase = true) == true ||
                    exception.message?.contains("waiting", ignoreCase = true) == true ||
                    exception.message?.contains("optional module", ignoreCase = true) == true
                } else {
                    exception.message?.contains("optional module", ignoreCase = true) == true
                }

                if (isModuleDownloading && retryCount < 3) {
                    // Trigger download programmatically just to be certain
                    try {
                        val moduleInstallClient = com.google.android.gms.common.moduleinstall.ModuleInstall.getClient(applicationContext)
                        val request = com.google.android.gms.common.moduleinstall.ModuleInstallRequest.newBuilder()
                            .addApi(recognizer)
                            .build()
                        moduleInstallClient.installModules(request)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    Toast.makeText(
                        this@FloatingLauncherService,
                        "Preparing text recognition (one-time setup)... Retrying in 3 seconds.",
                        Toast.LENGTH_LONG
                    ).show()

                    Handler(Looper.getMainLooper()).postDelayed({
                        processOcrAndSave(bitmap, retryCount + 1)
                    }, 3000)
                } else {
                    bitmap.recycle() // Clean up bitmap memory immediately on failure too
                    Toast.makeText(this@FloatingLauncherService, "OCR Failed: ${exception.message}", Toast.LENGTH_SHORT).show()
                }
            }
    }

    private fun dpToPx(dp: Float, context: Context): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning.value = false
        instance = null

        // Stop any running MediaProjection session and release references
        try {
            mediaProjection?.stop()
            mediaProjection = null
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Clean up screen selection overlay
        removeSelectionOverlay()

        // Unregister screen state listener dynamically to prevent memory leaks and battery drainage
        screenReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            screenReceiver = null
        }

        // Unregister package alterations listener dynamically to prevent memory leaks and battery drainage
        packageReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            packageReceiver = null
        }

        // Cancel the background preloader scope immediately
        try {
            serviceScope.cancel()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Safely cancel any running UI/snap transitions
        snapAnimator?.cancel()
        snapAnimator = null

        // Remove floating view safely from WindowManager and garbage collect all references
        bubbleView?.let { view ->
            try {
                if (isViewAdded) {
                    windowManager?.removeViewImmediate(view)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        bubbleView = null
        bubbleImageView = null
        windowManager = null
        isViewAdded = false
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}

class ServiceLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    init {
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override val viewModelStore: ViewModelStore
        get() = store

    fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }
}
