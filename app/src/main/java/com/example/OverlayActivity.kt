package com.example

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
<<<<<<< HEAD
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
=======
>>>>>>> c93703b (Fix: restore real Tesseract trained data files (eng + ara))
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ui.theme.MyApplicationTheme
import androidx.compose.ui.res.stringResource
import com.example.R
import com.example.ui.theme.CardDark
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*
import com.example.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable

class OverlayActivity : ComponentActivity() {

    companion object {
        val activeTabFlow = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activeTabFlow.value = intent.getStringExtra("launch_tab")
        setContent {
            val context = LocalContext.current
            val activeTheme = remember { ThemePreferences.getSelectedTheme(context) }
            MyApplicationTheme(accentColor = activeTheme.getColor()) {
                OverlayScreen(onDismiss = { finish() })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        activeTabFlow.value = intent.getStringExtra("launch_tab")
    }
}

@Composable
fun OverlayScreen(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val activeTheme = remember { ThemePreferences.getSelectedTheme(context) }
    val accentColor = activeTheme.getColor()

    val coroutineScope = rememberCoroutineScope()

    // Database and repository
    val database = remember { OrbitDatabase.getDatabase(context) }
    val repository = remember { OrbitRepository(database) }

    // Tab Selection state
    var selectedTab by rememberSaveable { mutableStateOf(OrbitTab.LAUNCHER) }

    // Observe activeTabFlow to switch tabs dynamically
    val activeTabExtra by OverlayActivity.activeTabFlow.collectAsState()
    LaunchedEffect(activeTabExtra) {
        if (activeTabExtra == "vault") {
            selectedTab = OrbitTab.VAULT
            OverlayActivity.activeTabFlow.value = null // Consume extra
        }
    }

    // Launcher tab states
    var appsList by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var sortedAlphabetically by remember { mutableStateOf(false) }
    var explanationReason by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    var favoritePackageNames by remember { mutableStateOf(FavoritesPreferences.getFavorites(context)) }

    // Vault tab states
    var vaultInputText by rememberSaveable { mutableStateOf("") }
    val savedVaultEntries by repository.vaultEntries.collectAsState(initial = emptyList())
    val filteredVaultEntries = remember(savedVaultEntries) {
        savedVaultEntries.filter { it.id != -1L }
    }
    var currentFolderId by rememberSaveable { mutableStateOf<Long?>(null) }
    var editingEntry by remember { mutableStateOf<VaultEntry?>(null) }
    val savedVaultFolders by repository.vaultFolders.collectAsState(initial = emptyList())

<<<<<<< HEAD
    // ----------------------------------------------------------------
    // Scan Screen (OCR) flow state.
    //   - isScanOverlayVisible: when true, the full-screen translucent
    //     ScreenCaptureOverlay is shown INSTEAD of the regular Vault UI.
    //   - pendingCaptureRegion: holds the user's selection between
    //     "Capture" tap and the MediaProjection consent dialog returning.
    //   - The MediaProjection consent dialog is launched ONCE per app
    //     session (ScreenCaptureManager.hasActiveProjection() short-
    //     circuits subsequent scans). This fixes the previous failure
    //     mode of re-prompting every single scan.
    // ----------------------------------------------------------------
    var isScanOverlayVisible by rememberSaveable { mutableStateOf(false) }
    var pendingCaptureRegion by remember { mutableStateOf<Rect?>(null) }

    // MediaProjection consent launcher. The Activity Result API handles the
    // startActivityForResult dance for us. On success we forward the result
    // to FloatingLauncherService which actually performs the capture+OCR.
    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        val region = pendingCaptureRegion
        if (result.resultCode == Activity.RESULT_OK && result.data != null && region != null) {
            // Forward the result + region to the foreground service. The service:
            //   1) promotes itself to FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION (10s rule)
            //   2) hides the bubble
            //   3) waits CAPTURE_SETTLE_DELAY_MS so our own UI is fully off-screen
            //   4) captures the region in-memory via MediaProjection
            //   5) runs Tesseract OCR (eng+ara, PSM_SINGLE_BLOCK, with preprocessing)
            //   6) saves the result to Vault with source="OCR"
            //   7) restores the bubble + reopens this activity on the Vault tab
            val serviceIntent = Intent(context, FloatingLauncherService::class.java).apply {
                action = FloatingLauncherService.ACTION_CAPTURE_SCREEN
                putExtra(FloatingLauncherService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(FloatingLauncherService.EXTRA_RESULT_DATA, result.data)
                putExtra(FloatingLauncherService.EXTRA_CAPTURE_REGION, region)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            // Dismiss the overlay activity NOW so the captured frame shows the
            // underlying app, not Orbit's own UI. The service's named settle delay
            // (CAPTURE_SETTLE_DELAY_MS) gives our exit animation time to complete.
            animateDismiss()
        } else {
            Toast.makeText(
                context,
                "Screen capture permission denied or no region selected.",
                Toast.LENGTH_SHORT
            ).show()
            pendingCaptureRegion = null
            isScanOverlayVisible = false
        }
    }

=======
>>>>>>> c93703b (Fix: restore real Tesseract trained data files (eng + ara))
    // Calculator tab states
    var calculatorInputText by rememberSaveable { mutableStateOf("") }

    // Speed Dial tab states
    var speedDialLabelText by rememberSaveable { mutableStateOf("") }
    var speedDialUrlText by rememberSaveable { mutableStateOf("") }
    val speedDialEntries by repository.speedDialEntries.collectAsState(initial = emptyList())

    // Spring scale and alpha animations for elastic bouncy effect
    var animatePlay by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        animatePlay = true
    }

    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (animatePlay) 1f else 0.8f,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessLow
        )
    )
    val alpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (animatePlay) 1f else 0f,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessLow
        )
    )

    fun animateDismiss() {
        animatePlay = false
    }

    LaunchedEffect(animatePlay) {
        if (!animatePlay) {
            delay(220)
            onDismiss()
        }
    }

    // Intercept back button to dismiss the overlay
    BackHandler {
        animateDismiss()
    }

    // Load Vault active composition draft from Room on startup
    LaunchedEffect(Unit) {
        val draft = withContext(Dispatchers.IO) {
            database.vaultDao().getDraftEntry()
        }
        if (draft != null) {
            vaultInputText = draft.content
        }
    }

    // Debounce active Quick Vault composition draft to Room database
    LaunchedEffect(vaultInputText) {
        delay(400) // 400ms debounce
        withContext(Dispatchers.IO) {
            if (vaultInputText.isBlank()) {
                database.vaultDao().deleteDraft()
            } else {
                database.vaultDao().insert(VaultEntry(id = -1L, content = vaultInputText))
            }
        }
    }

    // Launcher tab initialization (loading apps)
    LaunchedEffect(Unit) {
        isLoading = true
        val list = withContext(Dispatchers.IO) {
            AppCache.getApps(context)
        }

        // Determine if we have usage stats data
        val totalUsage = list.sumOf { it.usageTimeMs }
        val hasUsageStats = hasUsagePermission(context)
        val isSkipped = ThemePreferences.isUsagePermissionSkipped(context)

        val collator = java.text.Collator.getInstance(Locale.getDefault())

        val sortedList = if (hasUsageStats && totalUsage > 0L) {
            sortedAlphabetically = false
            explanationReason = ""
            list.sortedByDescending { it.usageTimeMs }
        } else {
            sortedAlphabetically = true
            explanationReason = if (isSkipped) {
                "" // Do not show any error if the user skipped this permission
            } else if (!hasUsageStats) {
                context.getString(R.string.overlay_no_usage_permission)
            } else {
                context.getString(R.string.overlay_no_usage_stats)
            }
            list.sortedWith { app1, app2 -> collator.compare(app1.label, app2.label) }
        }

        appsList = sortedList
        isLoading = false
    }

    // Dynamic list calculations based on favorites and search filter
    val filteredApps = remember(appsList, searchQuery) {
        if (searchQuery.isBlank()) {
            appsList
        } else {
            appsList.filter { it.label.contains(searchQuery, ignoreCase = true) }
        }
    }

    val favoriteApps = remember(filteredApps, favoritePackageNames) {
        filteredApps.filter { favoritePackageNames.contains(it.packageName) }
    }

    val regularApps = remember(filteredApps, favoritePackageNames) {
        filteredApps.filter { !favoritePackageNames.contains(it.packageName) }
    }

    // Toggle Favorite Action
    val onToggleFavorite: (AppInfo) -> Unit = { app ->
        if (favoritePackageNames.contains(app.packageName)) {
            FavoritesPreferences.removeFavorite(context, app.packageName)
        } else {
            FavoritesPreferences.addFavorite(context, app.packageName)
        }
        favoritePackageNames = FavoritesPreferences.getFavorites(context)
    }

    // Fullscreen backdrop
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                animateDismiss() // Dismiss when tapping outside the card
            },
        contentAlignment = Alignment.Center
    ) {
        // App Grid Container Card
        Card(
            modifier = Modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    this.alpha = alpha
                }
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.82f) // slightly taller to accommodate favorites and search bar
                .border(2.dp, accentColor, RoundedCornerShape(24.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    // Prevent dismissal when clicking inside the card
                },
            colors = CardDefaults.cardColors(containerColor = CardDark),
            shape = RoundedCornerShape(24.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp)
            ) {
                // Header Title
                Text(
                    text = stringResource(id = R.string.overlay_launchpad),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = accentColor,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Feature 4: Tab switcher bar below the title
                TabSwitcherBar(
                    selectedTab = selectedTab,
                    onTabSelected = { selectedTab = it },
                    accentColor = accentColor
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Tab Content Switcher with exact visibility conditions
                Box(modifier = Modifier.weight(1f)) {
                    when (selectedTab) {
                        OrbitTab.LAUNCHER -> {
                            LauncherTabContent(
                                isLoading = isLoading,
                                searchQuery = searchQuery,
                                onSearchQueryChange = { searchQuery = it },
                                filteredApps = filteredApps,
                                favoriteApps = favoriteApps,
                                regularApps = regularApps,
                                onToggleFavorite = onToggleFavorite,
                                onDismiss = { animateDismiss() },
                                sortedAlphabetically = sortedAlphabetically,
                                explanationReason = explanationReason,
                                accentColor = accentColor
                            )
                        }
                        OrbitTab.VAULT -> {
                            val displayEntries = remember(filteredVaultEntries, currentFolderId) {
                                filteredVaultEntries.filter { it.folderId == currentFolderId }
                            }
                            val currentFolderName = remember(savedVaultFolders, currentFolderId) {
                                savedVaultFolders.find { it.id == currentFolderId }?.name ?: "Root"
                            }

                            VaultTabContent(
                                vaultText = vaultInputText,
                                onVaultTextChange = { vaultInputText = it },
                                savedEntries = displayEntries,
                                folders = savedVaultFolders,
                                currentFolderId = currentFolderId,
                                currentFolderName = currentFolderName,
                                onCurrentFolderIdChange = { currentFolderId = it },
                                onCreateFolder = { folderName ->
                                    coroutineScope.launch(Dispatchers.IO) {
                                        repository.insertVaultFolder(VaultFolder(name = folderName))
                                    }
                                },
                                onDeleteFolder = { folder ->
                                    coroutineScope.launch(Dispatchers.IO) {
                                        repository.deleteVaultFolder(folder)
                                        if (currentFolderId == folder.id) {
                                            withContext(Dispatchers.Main) {
                                                currentFolderId = null
                                            }
                                        }
                                    }
                                },
                                onSaveEntry = {
                                    if (vaultInputText.isNotBlank()) {
                                        coroutineScope.launch(Dispatchers.IO) {
                                            val currentEditing = editingEntry
                                            if (currentEditing != null) {
                                                val updated = currentEditing.copy(content = vaultInputText)
                                                repository.updateVaultEntry(updated)
                                                withContext(Dispatchers.Main) {
                                                    editingEntry = null
                                                    vaultInputText = ""
                                                }
                                            } else {
                                                repository.insertVaultEntry(
                                                    VaultEntry(
                                                        content = vaultInputText,
                                                        folderId = currentFolderId
                                                    )
                                                )
                                                withContext(Dispatchers.Main) {
                                                    vaultInputText = ""
                                                }
                                            }
                                        }
                                    }
                                },
                                onDeleteEntry = { entry ->
                                    coroutineScope.launch(Dispatchers.IO) {
                                        repository.deleteVaultEntry(entry)
                                        if (editingEntry?.id == entry.id) {
                                            withContext(Dispatchers.Main) {
                                                editingEntry = null
                                                vaultInputText = ""
                                            }
                                        }
                                    }
                                },
                                editingEntry = editingEntry,
                                onStartEditing = { entry ->
                                    editingEntry = entry
                                    vaultInputText = entry?.content ?: ""
                                },
                                accentColor = accentColor,
                                onScanScreen = {
                                    val intent = Intent(context, com.example.ocr.ScreenSelectionActivity::class.java).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                    // Dismiss this launchpad overlay immediately; ScreenSelectionActivity
                                    // hides the bubble itself and reopens the Vault tab when the scan finishes.
                                    animateDismiss()
                                }
                            )
                        }
                        OrbitTab.CALCULATOR -> {
                            CalculatorTabContent(
                                calculatorInput = calculatorInputText,
                                onCalculatorInputChange = { calculatorInputText = it },
                                accentColor = accentColor
                            )
                        }
                        OrbitTab.SPEED_DIAL -> {
                            SpeedDialTabContent(
                                speedDialLabel = speedDialLabelText,
                                onSpeedDialLabelChange = { speedDialLabelText = it },
                                speedDialUrl = speedDialUrlText,
                                onSpeedDialUrlChange = { speedDialUrlText = it },
                                speedDialEntries = speedDialEntries,
                                onSaveLink = {
                                    coroutineScope.launch(Dispatchers.IO) {
                                        repository.insertSpeedDialEntry(
                                            SpeedDialEntry(
                                                label = speedDialLabelText,
                                                url = speedDialUrlText,
                                                sortOrder = speedDialEntries.size
                                            )
                                        )
                                        withContext(Dispatchers.Main) {
                                            speedDialLabelText = ""
                                            speedDialUrlText = ""
                                        }
                                    }
                                },
                                onDeleteLink = { entry ->
                                    coroutineScope.launch(Dispatchers.IO) {
                                        repository.deleteSpeedDialEntry(entry)
                                    }
                                },
                                accentColor = accentColor
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Close Button
                Button(
                    onClick = { animateDismiss() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, TextSecondary.copy(alpha = 0.4f), RoundedCornerShape(12.dp)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(id = R.string.overlay_close), color = TextSecondary, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
fun AppGridItem(
    app: AppInfo,
    isFavorite: Boolean,
    accentColor: Color,
    onToggleFavorite: () -> Unit,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(4.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Render App Icon safely with a futuristic glowing border
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(Color(0x0A00E5FF), RoundedCornerShape(12.dp))
                    .border(
                        width = 1.dp,
                        color = if (isFavorite) Color(0xFFFFD600) else Color.Transparent,
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(4.dp),
                contentAlignment = Alignment.Center
            ) {
                if (app.icon != null) {
                    AndroidView(
                        factory = { ctx ->
                            ImageView(ctx).apply {
                                scaleType = ImageView.ScaleType.FIT_CENTER
                            }
                        },
                        update = { imageView ->
                            imageView.setImageDrawable(app.icon)
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Gray.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // App Label
            Text(
                text = app.label,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                color = Color.White,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Small Star Button overlayed on top right
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 6.dp, y = (-6).dp)
                .size(24.dp)
                .background(Color(0xFF151D33), RoundedCornerShape(12.dp))
                .clickable { onToggleFavorite() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isFavorite) Icons.Filled.Star else Icons.Outlined.Star,
                contentDescription = "Favorite",
                tint = if (isFavorite) Color(0xFFFFD600) else TextSecondary.copy(alpha = 0.5f),
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

private fun hasUsagePermission(context: Context): Boolean {
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
