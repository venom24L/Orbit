package com.example

import android.content.Context
import android.widget.ImageView
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.VaultEntry
import com.example.data.SpeedDialEntry
import com.example.ui.theme.TextSecondary

@Composable
fun TabSwitcherBar(
    selectedTab: OrbitTab,
    onTabSelected: (OrbitTab) -> Unit,
    accentColor: Color
) {
    Row(
        modifier = Modifier
            .fillOuterWidth()
            .height(56.dp)
            .background(Color(0x08FFFFFF), RoundedCornerShape(12.dp))
            .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val tabs = listOf(
            Triple(OrbitTab.LAUNCHER, Icons.Default.Apps, "Launcher"),
            Triple(OrbitTab.VAULT, Icons.Default.Book, "Vault"),
            Triple(OrbitTab.CALCULATOR, Icons.Default.Calculate, "Calculator"),
            Triple(OrbitTab.SPEED_DIAL, Icons.Default.Link, "Speed Dial")
        )

        tabs.forEach { (tab, icon, label) ->
            val isSelected = selectedTab == tab
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) accentColor.copy(alpha = 0.15f) else Color.Transparent)
                    .clickable { onTabSelected(tab) }
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = label,
                        tint = if (isSelected) accentColor else TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = label,
                        fontSize = 10.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) accentColor else TextSecondary
                    )
                }
            }
        }
    }
}

private fun Modifier.fillOuterWidth(): Modifier = this.fillMaxWidth()

@Composable
fun LauncherTabContent(
    isLoading: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    filteredApps: List<AppInfo>,
    favoriteApps: List<AppInfo>,
    regularApps: List<AppInfo>,
    onToggleFavorite: (AppInfo) -> Unit,
    onDismiss: () -> Unit,
    sortedAlphabetically: Boolean,
    explanationReason: String,
    accentColor: Color
) {
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxSize()) {
        Spacer(modifier = Modifier.height(8.dp))

        // Sorting Subtitle / Explanation
        if (sortedAlphabetically && searchQuery.isEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x11FF1744), RoundedCornerShape(8.dp))
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "Alert",
                    tint = Color(0xFFFF4D4D),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = explanationReason,
                    color = Color(0xFFFFB3B3),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        } else if (searchQuery.isEmpty()) {
            Text(
                text = stringResource(id = R.string.overlay_most_used),
                color = TextSecondary,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Search Bar Input Field
        TextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            placeholder = { Text(stringResource(id = R.string.overlay_search_placeholder), color = TextSecondary, fontSize = 14.sp) },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color(0x1400E5FF),
                unfocusedContainerColor = Color(0x0AFFFFFF),
                focusedIndicatorColor = accentColor,
                unfocusedIndicatorColor = Color.Transparent,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(12.dp)),
            singleLine = true,
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    tint = accentColor
                )
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (isLoading) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = accentColor)
            }
        } else if (filteredApps.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(id = R.string.overlay_no_matching),
                    color = TextSecondary,
                    fontSize = 14.sp
                )
            }
        } else {
            // Unified Grid using span support to build clean Sections (Favorites vs Regular Apps)
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Section: Favorites (Show only when not searching)
                if (favoriteApps.isNotEmpty() && searchQuery.isEmpty()) {
                    item(span = { GridItemSpan(4) }) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                        ) {
                            Text(
                                text = stringResource(id = R.string.overlay_favorites),
                                color = accentColor,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            HorizontalDivider(
                                color = accentColor.copy(alpha = 0.2f),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    items(favoriteApps) { app ->
                        AppGridItem(
                            app = app,
                            isFavorite = true,
                            accentColor = accentColor,
                            onToggleFavorite = { onToggleFavorite(app) },
                            onClick = {
                                val pm = context.packageManager
                                val launchIntent = pm.getLaunchIntentForPackage(app.packageName)
                                if (launchIntent != null) {
                                    context.startActivity(launchIntent)
                                    onDismiss()
                                } else {
                                    Toast.makeText(context, context.getString(R.string.overlay_cannot_open), Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }

                    item(span = { GridItemSpan(4) }) {
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }

                // Section: Regular or Filtered Apps
                item(span = { GridItemSpan(4) }) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 4.dp)
                    ) {
                        Text(
                            text = if (searchQuery.isNotEmpty()) stringResource(id = R.string.overlay_search_results) else stringResource(id = R.string.overlay_all_apps),
                            color = accentColor,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        HorizontalDivider(
                            color = accentColor.copy(alpha = 0.2f),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                items(regularApps) { app ->
                    AppGridItem(
                        app = app,
                        isFavorite = false,
                        accentColor = accentColor,
                        onToggleFavorite = { onToggleFavorite(app) },
                        onClick = {
                            val pm = context.packageManager
                            val launchIntent = pm.getLaunchIntentForPackage(app.packageName)
                            if (launchIntent != null) {
                                context.startActivity(launchIntent)
                                onDismiss()
                            } else {
                                Toast.makeText(context, context.getString(R.string.overlay_cannot_open), Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun VaultTabContent(
    vaultText: String,
    onVaultTextChange: (String) -> Unit,
    savedEntries: List<VaultEntry>,
    onSaveEntry: () -> Unit,
    onDeleteEntry: (VaultEntry) -> Unit,
    accentColor: Color
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 4.dp)
    ) {
        // Compose section
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = vaultText,
                onValueChange = onVaultTextChange,
                placeholder = { Text("Write a quick note, idea, or paste text...", color = TextSecondary, fontSize = 13.sp) },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color(0x1400E5FF),
                    unfocusedContainerColor = Color(0x0AFFFFFF),
                    focusedIndicatorColor = accentColor,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(80.dp)
                    .clip(RoundedCornerShape(12.dp)),
                maxLines = 4
            )
            
            Spacer(modifier = Modifier.width(8.dp))
            
            IconButton(
                onClick = onSaveEntry,
                enabled = vaultText.isNotBlank(),
                modifier = Modifier
                    .size(50.dp)
                    .background(
                        if (vaultText.isNotBlank()) accentColor else Color.White.copy(alpha = 0.05f),
                        RoundedCornerShape(12.dp)
                    )
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Save Note",
                    tint = if (vaultText.isNotBlank()) Color.Black else TextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Saved Scratchpad Vault",
            color = accentColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 6.dp)
        )

        if (savedEntries.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Vault is empty. Save text clips above!",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(savedEntries) { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0x0AFFFFFF), RoundedCornerShape(10.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(10.dp))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = entry.content,
                                color = Color.White,
                                fontSize = 13.sp,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        
                        Spacer(modifier = Modifier.width(8.dp))
                        
                        IconButton(
                            onClick = { onDeleteEntry(entry) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete entry",
                                tint = Color(0xFFFF4D4D),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CalculatorTabContent(
    calculatorInput: String,
    onCalculatorInputChange: (String) -> Unit,
    accentColor: Color
) {
    var resultText by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf("") }

    // Synchronously parse and evaluate the live input expression
    LaunchedEffect(calculatorInput) {
        if (calculatorInput.isBlank()) {
            resultText = ""
            errorText = ""
            return@LaunchedEffect
        }
        try {
            val res = ExpressionEvaluator.evaluate(calculatorInput)
            // Format nice result: omit decimal if it's a whole number
            resultText = if (res % 1 == 0.0) {
                res.toLong().toString()
            } else {
                String.format("%.4f", res).trimEnd('0').trimEnd('.')
            }
            errorText = ""
        } catch (e: Exception) {
            resultText = ""
            errorText = e.message ?: "Invalid Expression"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.Top
    ) {
        Text(
            text = "Floating Calculator",
            color = accentColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // Mathematical input field
        TextField(
            value = calculatorInput,
            onValueChange = onCalculatorInputChange,
            placeholder = { Text("Enter expression (e.g. 2 * (3 + 4.5))", color = TextSecondary, fontSize = 13.sp) },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color(0x1400E5FF),
                unfocusedContainerColor = Color(0x0AFFFFFF),
                focusedIndicatorColor = accentColor,
                unfocusedIndicatorColor = Color.Transparent,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(12.dp)),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Large Output Card for the evaluation result or error
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0x05FFFFFF), RoundedCornerShape(14.dp))
                .border(1.dp, Color.White.copy(alpha = 0.03f), RoundedCornerShape(14.dp))
                .padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Result",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                if (errorText.isNotEmpty()) {
                    Text(
                        text = errorText,
                        color = Color(0xFFFF4D4D),
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.SemiBold
                    )
                } else {
                    Text(
                        text = resultText.ifEmpty { "0" },
                        color = if (resultText.isNotEmpty()) accentColor else Color.White.copy(alpha = 0.4f),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Help quick buttons/keys representation
        Text(
            text = "Supported operators: + - * / ( ) .",
            color = TextSecondary,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun SpeedDialTabContent(
    speedDialLabel: String,
    onSpeedDialLabelChange: (String) -> Unit,
    speedDialUrl: String,
    onSpeedDialUrlChange: (String) -> Unit,
    speedDialEntries: List<SpeedDialEntry>,
    onSaveLink: () -> Unit,
    onDeleteLink: (SpeedDialEntry) -> Unit,
    accentColor: Color
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 4.dp)
    ) {
        // Inline layout to Add a link
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                TextField(
                    value = speedDialLabel,
                    onValueChange = onSpeedDialLabelChange,
                    placeholder = { Text("Label (e.g. Google)", color = TextSecondary, fontSize = 14.sp) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color(0x1400E5FF),
                        unfocusedContainerColor = Color(0x0AFFFFFF),
                        focusedIndicatorColor = accentColor,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                TextField(
                    value = speedDialUrl,
                    onValueChange = onSpeedDialUrlChange,
                    placeholder = { Text("URL (e.g. google.com)", color = TextSecondary, fontSize = 14.sp) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color(0x1400E5FF),
                        unfocusedContainerColor = Color(0x0AFFFFFF),
                        focusedIndicatorColor = accentColor,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    singleLine = true
                )
            }
            
            Spacer(modifier = Modifier.width(8.dp))
            
            IconButton(
                onClick = onSaveLink,
                enabled = speedDialLabel.isNotBlank() && speedDialUrl.isNotBlank(),
                modifier = Modifier
                    .size(112.dp)
                    .background(
                        if (speedDialLabel.isNotBlank() && speedDialUrl.isNotBlank()) accentColor else Color.White.copy(alpha = 0.05f),
                        RoundedCornerShape(12.dp)
                    )
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add Link",
                    tint = if (speedDialLabel.isNotBlank() && speedDialUrl.isNotBlank()) Color.Black else TextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Speed Dial Links",
            color = accentColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 6.dp)
        )

        if (speedDialEntries.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No saved links yet. Add your favorite websites above!",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        } else {
            // Display links in 4 columns like app icons
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(speedDialEntries) { entry ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                // Launch Chrome Custom Tab safely
                                val formattedUrl = if (!entry.url.startsWith("http://") && !entry.url.startsWith("https://")) {
                                    "https://${entry.url}"
                                } else {
                                    entry.url
                                }
                                try {
                                    val uri = Uri.parse(formattedUrl)
                                    val customTabsIntent = CustomTabsIntent.Builder()
                                        .setShowTitle(true)
                                        .build()
                                    customTabsIntent.launchUrl(context, uri)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Invalid link: ${entry.url}", Toast.LENGTH_SHORT).show()
                                }
                            }
                            .padding(4.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Link Icon styling matches app-grid item
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(Color(0x0A00E5FF), RoundedCornerShape(12.dp))
                                    .border(
                                        width = 1.dp,
                                        color = accentColor.copy(alpha = 0.3f),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    .padding(4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                // Dynamic letter-based or globe icon
                                val letter = entry.label.trim().firstOrNull()?.uppercase() ?: "W"
                                Text(
                                    text = letter.toString(),
                                    color = accentColor,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            // Link label text
                            Text(
                                text = entry.label,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                color = Color.White,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // Small close/delete button overlayed on top right
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 6.dp, y = (-6).dp)
                                .size(22.dp)
                                .background(Color(0xFF151D33), RoundedCornerShape(11.dp))
                                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(11.dp))
                                .clickable { onDeleteLink(entry) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Delete",
                                tint = Color(0xFFFF4D4D),
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
