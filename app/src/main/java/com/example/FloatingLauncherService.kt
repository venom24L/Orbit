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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.math.hypot

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

        @Volatile
        var instance: FloatingLauncherService? = null
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        isServiceRunning.value = true
        instance = this

        registerScreenReceiver()
        registerPackageReceiver()

        // Preload in-memory cache in background to achieve instant launch
        serviceScope.launch {
            AppCache.getApps(this@FloatingLauncherService)
        }
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

    /**
     * Public entry point used by the OCR capture flow (see com.example.ocr.ScreenSelectionActivity)
     * to restore the bubble after a scan completes. Delegates to the existing private logic so
     * behavior stays identical to the manual show/hide path used elsewhere in this service.
     */
    fun showBubbleForOcrRestore() {
        showBubble()
    }

    /**
     * Public entry point used by the OCR capture flow to hide the bubble immediately before a
     * screen capture, so the captured frame shows the underlying app/content rather than
     * Orbit's own floating bubble.
     */
    fun hideBubbleForOcrCapture() {
        hideBubble()
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


    private fun dpToPx(dp: Float, context: Context): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning.value = false
        instance = null

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
