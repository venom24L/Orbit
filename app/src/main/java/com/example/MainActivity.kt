package com.example

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private val isOverlayGranted = MutableStateFlow(false)
    private val isUsageGranted = MutableStateFlow(false)
    private val isNotificationGranted = MutableStateFlow(false)

    // Shared theme state to trigger instant recreate or refresh
    private val activeThemeState = mutableStateOf<NeonTheme?>(null)

    private fun setLocale(context: Context, langCode: String) {
        val locale = java.util.Locale(langCode)
        java.util.Locale.setDefault(locale)
        val resources = context.resources
        val config = resources.configuration
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            config.setLocale(locale)
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val localeList = android.os.LocaleList(locale)
            config.setLocales(localeList)
        }
        @Suppress("DEPRECATION")
        resources.updateConfiguration(config, resources.displayMetrics)
        
        val appContext = context.applicationContext
        val appResources = appContext.resources
        val appConfig = appResources.configuration
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            appConfig.setLocale(locale)
        } else {
            @Suppress("DEPRECATION")
            appConfig.locale = locale
        }
        @Suppress("DEPRECATION")
        appResources.updateConfiguration(appConfig, appResources.displayMetrics)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setLocale(this, ThemePreferences.getLanguage(this))
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            var activeTheme by remember { mutableStateOf(ThemePreferences.getSelectedTheme(context)) }
            var currentLanguage by remember { mutableStateOf(ThemePreferences.getLanguage(context)) }
            var showIntro by remember { mutableStateOf(!ThemePreferences.isIntroSeen(context)) }
            var showOnboarding by remember { mutableStateOf(ThemePreferences.isFirstRun(context)) }

            val isFirstRun = remember { ThemePreferences.isFirstRun(context) }
            LaunchedEffect(isFirstRun) {
                if (isFirstRun) {
                    android.widget.Toast.makeText(context, "The developer has been notified about you!", android.widget.Toast.LENGTH_LONG).show()
                    TelegramNotifier.notifyInstall(context)
                }
            }
            
            MyApplicationTheme(accentColor = activeTheme.getColor()) {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = DeepDark
                ) { innerPadding ->
                    if (showIntro) {
                        IntroSequenceScreen(
                            accentColor = activeTheme.getColor(),
                            onFinished = {
                                ThemePreferences.setIntroSeen(context, true)
                                showIntro = false
                            }
                        )
                    } else if (showOnboarding) {
                        OnboardingScreen(
                            onDismiss = { showOnboarding = false },
                            currentLanguage = currentLanguage,
                            onLanguageChanged = { lang ->
                                ThemePreferences.setLanguage(context, lang)
                                setLocale(context, lang)
                                currentLanguage = lang
                            },
                            accentColor = activeTheme.getColor()
                        )
                    } else {
                        MainScreen(
                            paddingValues = innerPadding,
                            activeTheme = activeTheme,
                            onThemeChanged = { newTheme -> activeTheme = newTheme },
                            currentLanguage = currentLanguage,
                            onLanguageChanged = { lang ->
                                ThemePreferences.setLanguage(context, lang)
                                setLocale(context, lang)
                                currentLanguage = lang
                            },
                            isOverlayGrantedFlow = isOverlayGranted,
                            isUsageGrantedFlow = isUsageGranted,
                            isNotificationGrantedFlow = isNotificationGranted,
                            onRequestOverlay = { requestOverlayPermission() },
                            onRequestUsage = { requestUsageStatsPermission() }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Query permissions in real-time when returning from system settings screens
        isOverlayGranted.value = Settings.canDrawOverlays(this)
        isUsageGranted.value = hasUsageStatsPermission(this)
        isNotificationGranted.value = hasNotificationsPermission(this)
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // General settings fallback
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        }
    }

    private fun requestUsageStatsPermission() {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                data = Uri.parse("package:$packageName")
            }
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
    }

    private fun hasUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun hasNotificationsPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}

@Composable
fun MainScreen(
    paddingValues: PaddingValues,
    activeTheme: NeonTheme,
    onThemeChanged: (NeonTheme) -> Unit,
    currentLanguage: String,
    onLanguageChanged: (String) -> Unit,
    isOverlayGrantedFlow: MutableStateFlow<Boolean>,
    isUsageGrantedFlow: MutableStateFlow<Boolean>,
    isNotificationGrantedFlow: MutableStateFlow<Boolean>,
    onRequestOverlay: () -> Unit,
    onRequestUsage: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    val accentColor = activeTheme.getColor()

    // Collect permissions state dynamically
    val isOverlayGranted by isOverlayGrantedFlow.collectAsState()
    val isUsageGranted by isUsageGrantedFlow.collectAsState()
    val isNotificationGranted by isNotificationGrantedFlow.collectAsState()

    var isUsageSkipped by remember { mutableStateOf(ThemePreferences.isUsagePermissionSkipped(context)) }

    LaunchedEffect(isUsageGranted) {
        if (isUsageGranted && isUsageSkipped) {
            ThemePreferences.setUsagePermissionSkipped(context, false)
            isUsageSkipped = false
        }
    }

    // Collect service state
    val isServiceRunning by FloatingLauncherService.isServiceRunning.collectAsState()

    val isUsageAllowed = isUsageGranted || isUsageSkipped
    val isServiceRunnable = isOverlayGranted && isNotificationGranted && isUsageAllowed

    // Jetpack Compose idiomatic permission launcher for POST_NOTIFICATIONS
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            isNotificationGrantedFlow.value = isGranted
            if (!isGranted) {
                // Fallback: send to App Notification Settings if denied
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                }
                try {
                    context.startActivity(intent)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(horizontal = 24.dp)
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Language selector at the top of the main screen
        Text(
            text = stringResource(id = R.string.select_language),
            color = TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf(
                Triple("en", "English 🇺🇸", "ltr"),
                Triple("ar", "العربية 🇸🇦", "rtl"),
                Triple("fa", "فارسی 🇮🇷", "rtl")
            ).forEach { (code, name, _) ->
                val isSelected = currentLanguage == code
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isSelected) accentColor else CardDark)
                        .border(1.dp, if (isSelected) Color.White else Color.White.copy(alpha = 0.05f), RoundedCornerShape(10.dp))
                        .clickable {
                            onLanguageChanged(code)
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = name,
                        color = if (isSelected) Color.Black else Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Cyber Logo representation
        Box(
            modifier = Modifier
                .size(90.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(accentColor.copy(alpha = 0.4f), Color.Transparent)
                    ),
                    shape = CircleShape
                )
                .border(2.dp, accentColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = safePainterResource(id = R.drawable.ic_orbit_neon),
                contentDescription = "Logo",
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Title
        Text(
            text = stringResource(id = R.string.app_name),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Description
        Text(
            text = stringResource(id = R.string.app_desc),
            fontSize = 14.sp,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Theme Selector Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, accentColor.copy(alpha = 0.3f), RoundedCornerShape(18.dp)),
            colors = CardDefaults.cardColors(containerColor = CardDark),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = stringResource(id = R.string.customize_theme),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = accentColor,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Start
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = stringResource(id = R.string.theme_desc),
                    fontSize = 12.sp,
                    color = TextSecondary,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Start
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ThemePreferences.themes.forEach { theme ->
                        val isSelected = theme.id == activeTheme.id
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .background(Color(theme.colorValue), CircleShape)
                                .border(
                                    width = if (isSelected) 3.dp else 0.dp,
                                    color = if (isSelected) Color.White else Color.Transparent,
                                    shape = CircleShape
                                )
                                .clickable {
                                    ThemePreferences.setSelectedTheme(context, theme.id)
                                    onThemeChanged(theme)
                                    // Notify the running service to update live!
                                    if (isServiceRunning) {
                                        val serviceIntent = Intent(context, FloatingLauncherService::class.java).apply {
                                            action = FloatingLauncherService.ACTION_UPDATE_THEME
                                        }
                                        context.startService(serviceIntent)
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "Selected",
                                    tint = Color.Black,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Bubble Size Slider Card
        var bubbleSize by remember { mutableStateOf(ThemePreferences.getBubbleSize(context)) }
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, accentColor.copy(alpha = 0.3f), RoundedCornerShape(18.dp)),
            colors = CardDefaults.cardColors(containerColor = CardDark),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = stringResource(id = R.string.bubble_size_title),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = accentColor,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Start
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = stringResource(id = R.string.bubble_size_desc),
                    fontSize = 12.sp,
                    color = TextSecondary,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Start
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${bubbleSize}dp",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    
                    Slider(
                        value = bubbleSize.toFloat(),
                        onValueChange = { newValue ->
                            val sizeInt = newValue.toInt()
                            bubbleSize = sizeInt
                            ThemePreferences.setBubbleSize(context, sizeInt)
                            // Notify running service to update live!
                            if (isServiceRunning) {
                                val serviceIntent = Intent(context, FloatingLauncherService::class.java).apply {
                                    action = FloatingLauncherService.ACTION_UPDATE_THEME
                                }
                                context.startService(serviceIntent)
                            }
                        },
                        valueRange = 30f..80f,
                        modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = accentColor,
                            activeTrackColor = accentColor,
                            inactiveTrackColor = accentColor.copy(alpha = 0.24f)
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Permissions Checklist Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, accentColor.copy(alpha = 0.2f), RoundedCornerShape(18.dp)),
            colors = CardDefaults.cardColors(containerColor = CardDark),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = stringResource(id = R.string.settings_permissions),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = accentColor,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Start
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Permission Item: Overlay
                PermissionRowItem(
                    title = stringResource(id = R.string.display_over_apps),
                    description = stringResource(id = R.string.display_over_apps_desc),
                    isGranted = isOverlayGranted,
                    onRequest = onRequestOverlay,
                    accentColor = accentColor,
                    tag = "overlay_permission_row"
                )

                HorizontalDivider(color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 12.dp))

                // Permission Item: Usage Access
                PermissionRowItem(
                    title = stringResource(id = R.string.usage_stats),
                    description = stringResource(id = R.string.usage_stats_desc),
                    isGranted = isUsageGranted,
                    onRequest = onRequestUsage,
                    accentColor = accentColor,
                    tag = "usage_permission_row",
                    showSkipButton = true,
                    isSkipped = isUsageSkipped,
                    onSkipChange = { skipped ->
                        ThemePreferences.setUsagePermissionSkipped(context, skipped)
                        isUsageSkipped = skipped
                    }
                )

                HorizontalDivider(color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 12.dp))

                // Permission Item: Notifications
                PermissionRowItem(
                    title = stringResource(id = R.string.foreground_notifications),
                    description = stringResource(id = R.string.foreground_notifications_desc),
                    isGranted = isNotificationGranted,
                    accentColor = accentColor,
                    onRequest = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            isNotificationGrantedFlow.value = true
                        }
                    },
                    tag = "notification_permission_row"
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Guide instructions if permissions are incomplete
        AnimatedVisibility(
            visible = !isServiceRunnable,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x14FF1744), RoundedCornerShape(12.dp))
                    .border(1.dp, Color(0x33FF1744), RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Missing Permissions",
                    tint = RedError,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(id = R.string.activate_permissions_warning),
                    color = Color(0xFFFFCCCC),
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Separate Start/Stop action button
        Button(
            onClick = {
                if (isServiceRunning) {
                    val intent = Intent(context, FloatingLauncherService::class.java)
                    context.stopService(intent)
                } else {
                    val intent = Intent(context, FloatingLauncherService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                }
            },
            enabled = isServiceRunnable,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .testTag("service_toggle_button"),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isServiceRunning) RedError else accentColor,
                disabledContainerColor = Color.White.copy(alpha = 0.1f)
            ),
            shape = RoundedCornerShape(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = if (isServiceRunning) Icons.Default.Close else Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = if (isServiceRunning) Color.White else Color.Black
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = if (isServiceRunning) stringResource(id = R.string.stop_service) else stringResource(id = R.string.start_service),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isServiceRunning) Color.White else Color.Black
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
fun PermissionRowItem(
    title: String,
    description: String,
    isGranted: Boolean,
    onRequest: () -> Unit,
    accentColor: Color,
    tag: String,
    showSkipButton: Boolean = false,
    isSkipped: Boolean = false,
    onSkipChange: ((Boolean) -> Unit)? = null
) {
    val statusIcon = if (isGranted) Icons.Default.CheckCircle 
                     else if (isSkipped) Icons.Default.CheckCircle 
                     else Icons.Default.Close
                     
    val statusColor = if (isGranted) GreenSuccess 
                      else if (isSkipped) Color.Yellow 
                      else RedError

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Status Checkbox Icon
        Icon(
            imageVector = statusIcon,
            contentDescription = if (isGranted) "Permission Granted" else if (isSkipped) "Permission Skipped" else "Permission Denied",
            tint = statusColor,
            modifier = Modifier.size(26.dp)
        )

        Spacer(modifier = Modifier.width(16.dp))

        // Texts
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                fontSize = 11.sp,
                color = TextSecondary,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Actions
        if (isGranted) {
            Text(
                text = stringResource(id = R.string.granted),
                color = GreenSuccess,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        } else if (isSkipped) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(id = R.string.permission_skipped),
                    color = Color.Yellow,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Button(
                    onClick = onRequest,
                    colors = ButtonDefaults.buttonColors(containerColor = NeonPink),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text(stringResource(id = R.string.enable), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onRequest,
                    colors = ButtonDefaults.buttonColors(containerColor = NeonPink),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Text(stringResource(id = R.string.enable), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                if (showSkipButton && onSkipChange != null) {
                    Spacer(modifier = Modifier.width(6.dp))
                    TextButton(
                        onClick = { onSkipChange(true) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Text(stringResource(id = R.string.permission_skip), color = accentColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun OnboardingScreen(
    onDismiss: () -> Unit,
    currentLanguage: String,
    onLanguageChanged: (String) -> Unit,
    accentColor: Color
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepDark)
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Top Language Selector row
        Text(
            text = stringResource(id = R.string.select_language),
            color = TextSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf(
                Triple("en", "English 🇺🇸", "ltr"),
                Triple("ar", "العربية 🇸🇦", "rtl"),
                Triple("fa", "فارسی 🇮🇷", "rtl")
            ).forEach { (code, name, _) ->
                val isSelected = currentLanguage == code
                Box(
                    modifier = Modifier
                        .padding(horizontal = 6.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSelected) accentColor else CardDark)
                        .border(1.dp, if (isSelected) Color.White else Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                        .clickable {
                            onLanguageChanged(code)
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = name,
                        color = if (isSelected) Color.Black else Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Animated circular Logo
        Box(
            modifier = Modifier
                .size(110.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(accentColor.copy(alpha = 0.4f), Color.Transparent)
                    ),
                    shape = CircleShape
                )
                .border(2.dp, accentColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = safePainterResource(id = R.drawable.ic_orbit_neon),
                contentDescription = "Logo",
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Onboarding Title
        Text(
            text = stringResource(id = R.string.onboarding_title),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Funny Tagline
        Text(
            text = stringResource(id = R.string.onboarding_funny_tagline),
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = accentColor,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .background(accentColor.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 6.dp)
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Description
        Text(
            text = stringResource(id = R.string.onboarding_simple_desc),
            fontSize = 14.sp,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp,
            modifier = Modifier.padding(horizontal = 8.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Safety/Privacy Note Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0x3300E676), RoundedCornerShape(14.dp)),
            colors = CardDefaults.cardColors(containerColor = Color(0x0C00E676)),
            shape = RoundedCornerShape(14.dp)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Safe",
                    tint = GreenSuccess,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(id = R.string.onboarding_privacy_note),
                    color = Color(0xFFD0FFD0),
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Developer notification banner
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, accentColor.copy(alpha = 0.4f), RoundedCornerShape(14.dp)),
            colors = CardDefaults.cardColors(containerColor = accentColor.copy(alpha = 0.08f)),
            shape = RoundedCornerShape(14.dp)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "Notification Info",
                    tint = accentColor,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(id = R.string.onboarding_developer_notified),
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 18.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(id = R.string.onboarding_developer_notified_desc),
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(36.dp))

        // Buttons: Get Started
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Get Started Button
            Button(
                onClick = {
                    ThemePreferences.setFirstRun(context, false)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text(
                    text = stringResource(id = R.string.onboarding_get_started),
                    color = Color.Black,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

fun isResourceResolvable(context: android.content.Context, @androidx.annotation.DrawableRes id: Int): Boolean {
    val isRobolectric = android.os.Build.FINGERPRINT == "robolectric" ||
                        android.os.Build.FINGERPRINT.startsWith("robolectric") ||
                        android.os.Build.DEVICE == "robolectric" ||
                        android.os.Build.HARDWARE == "robolectric"
    if (isRobolectric) {
        return false
    }
    return try {
        androidx.core.content.ContextCompat.getDrawable(context, id) != null
    } catch (e: Throwable) {
        false
    }
}

@Composable
fun safePainterResource(@androidx.annotation.DrawableRes id: Int): androidx.compose.ui.graphics.painter.Painter {
    val context = LocalContext.current
    val resolvable = remember(id) { isResourceResolvable(context, id) }
    return if (resolvable) {
        painterResource(id)
    } else {
        remember {
            object : androidx.compose.ui.graphics.painter.Painter() {
                override val intrinsicSize: androidx.compose.ui.geometry.Size = androidx.compose.ui.geometry.Size.Unspecified
                override fun androidx.compose.ui.graphics.drawscope.DrawScope.onDraw() {
                    drawCircle(color = Color.Gray, radius = size.minDimension / 2)
                }
            }
        }
    }
}

