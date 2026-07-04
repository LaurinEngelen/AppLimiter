package com.example.instaguard.ui.main

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation3.runtime.NavKey
import com.example.instaguard.AppMonitorService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

data class AppItem(val name: String, val packageName: String)

@Composable
fun MainScreen(
    onItemClick: (NavKey) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    // Preferences & State
    val prefs = remember { context.getSharedPreferences("instaguard_prefs", Context.MODE_PRIVATE) }
    var limitInput by remember { mutableStateOf(prefs.getFloat("limit_minutes", 5.0f).toString()) }
    var warningInput by remember { mutableStateOf(prefs.getInt("warning_seconds", 30).toString()) }
    var cooldownInput by remember { mutableStateOf(prefs.getFloat("cooldown_minutes", 10.0f).toString()) }

    // Selected packages blocklist
    var selectedPackages by remember {
        mutableStateOf(prefs.getStringSet("locked_packages", emptySet()) ?: emptySet())
    }

    // App list and search state
    var appsList by remember { mutableStateOf(emptyList<AppItem>()) }
    var searchQuery by remember { mutableStateOf("") }
    var isLoadingApps by remember { mutableStateOf(true) }

    // Live permission states
    var accessibilityEnabled by remember { mutableStateOf(false) }
    var overlayGranted by remember { mutableStateOf(false) }

    // Cooldown trigger state (drives general UI update ticks)
    var uiTickTrigger by remember { mutableStateOf(0L) }

    // Load installed applications list asynchronously
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            appsList = getInstalledApps(context)
            isLoadingApps = false
        }
    }

    // Periodic checks for permission & UI state updates
    LaunchedEffect(Unit) {
        while (true) {
            accessibilityEnabled = isAccessibilityServiceEnabled(context, AppMonitorService::class.java)
            overlayGranted = Settings.canDrawOverlays(context)
            uiTickTrigger = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }

    // Filter apps list based on search query
    val filteredApps = remember(searchQuery, appsList) {
        if (searchQuery.isBlank()) {
            appsList
        } else {
            appsList.filter {
                it.name.contains(searchQuery, ignoreCase = true) ||
                        it.packageName.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Header
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(bottom = 8.dp)
        ) {
            Text(
                text = "AppLimiter",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                fontSize = 32.sp
            )
            Text(
                text = "Digitaler Zeitwächter für deine Apps",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Active App Budgets & Cooldowns Display Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Aktive App-Budgets",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )

                if (selectedPackages.isEmpty()) {
                    Text(
                        text = "Keine Apps gesperrt. Wähle unten Apps aus, um deren Budget zu aktivieren.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                } else {
                    val limitMinutes = prefs.getFloat("limit_minutes", 5.0f)
                    val limitMs = (limitMinutes * 60 * 1000).toLong()

                    selectedPackages.forEach { pkg ->
                        val appLabel = remember(pkg, appsList) {
                            appsList.find { it.packageName == pkg }?.name ?: pkg.substringAfterLast('.')
                        }

                        // Read states dynamically utilizing uiTickTrigger to re-read SharedPreferences every second
                        val budget = remember(pkg, uiTickTrigger) { prefs.getLong("remaining_budget_ms_$pkg", limitMs) }
                        val lastExit = remember(pkg, uiTickTrigger) { prefs.getLong("last_exit_time_$pkg", 0L) }
                        val cooldownEnd = remember(pkg, uiTickTrigger) { prefs.getLong("cooldown_end_time_$pkg", 0L) }
                        val now = SystemClock.elapsedRealtime()

                        val isCooldownActive = now < cooldownEnd
                        val cooldownRemaining = if (isCooldownActive) cooldownEnd - now else 0L

                        val appRemainingMs = if (lastExit > 0 && (now - lastExit) >= 1 * 60 * 60 * 1000) {
                            limitMs
                        } else {
                            if (budget <= 0) limitMs else budget
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(appLabel, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                if (isCooldownActive) {
                                    val coolMins = cooldownRemaining / 1000 / 60
                                    val coolSecs = (cooldownRemaining / 1000) % 60
                                    Text(
                                        text = String.format("Gesperrt! Cooldown: %02d:%02d", coolMins, coolSecs),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                        fontWeight = FontWeight.Bold
                                    )
                                } else {
                                    val budgetMins = appRemainingMs / 1000 / 60
                                    val budgetSecs = (appRemainingMs / 1000) % 60
                                    Text(
                                        text = String.format("Budget: %02d:%02d verbleibend", budgetMins, budgetSecs),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            
                            OutlinedButton(
                                onClick = {
                                    prefs.edit()
                                        .putLong("remaining_budget_ms_$pkg", limitMs)
                                        .putLong("cooldown_end_time_$pkg", 0L)
                                        .putLong("last_exit_time_$pkg", SystemClock.elapsedRealtime())
                                        .apply()
                                    uiTickTrigger = SystemClock.elapsedRealtime() // Force UI update
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text("Reset", fontSize = 11.sp)
                            }
                        }
                    }
                    
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    
                    Text(
                        text = "Jede App hat ihr eigenes Limit. Die Ablaufzeit setzt sich nach 1h Inaktivität zurück.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        // App Selection Blocklist Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Gesperrte Apps verwalten",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "${selectedPackages.size} Apps ausgewählt",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Nach Apps suchen...") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                if (isLoadingApps) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else {
                    val listScrollState = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .verticalScroll(listScrollState),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        filteredApps.forEach { app ->
                            val isChecked = selectedPackages.contains(app.packageName)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val updated = selectedPackages.toMutableSet()
                                        if (isChecked) {
                                            updated.remove(app.packageName)
                                        } else {
                                            updated.add(app.packageName)
                                            // Initialize budget for new locked app
                                            val limitMinutes = prefs.getFloat("limit_minutes", 5.0f)
                                            val limitMs = (limitMinutes * 60 * 1000).toLong()
                                            prefs.edit().putLong("remaining_budget_ms_${app.packageName}", limitMs).apply()
                                        }
                                        selectedPackages = updated
                                        prefs.edit().putStringSet("locked_packages", updated).apply()
                                        uiTickTrigger = SystemClock.elapsedRealtime() // Force UI update
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = null // Handled by Row click
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(app.name, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        text = app.packageName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        
                        if (filteredApps.isEmpty()) {
                            Text(
                                text = "Keine Apps gefunden.",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // Permissions Section
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Berechtigungen",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )

                // Accessibility Permission
                PermissionRow(
                    title = "Eingabehilfe (Accessibility)",
                    subtitle = "Wird benötigt, um ausgewählte Apps zu erkennen und zu sperren.",
                    isGranted = accessibilityEnabled,
                    onClick = {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        context.startActivity(intent)
                    }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Overlay Permission
                PermissionRow(
                    title = "Über anderen Apps einblenden",
                    subtitle = "Wird benötigt, um Warnungen und Sperren anzuzeigen.",
                    isGranted = overlayGranted,
                    onClick = {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        context.startActivity(intent)
                    }
                )
            }
        }

        // Settings Section
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Einstellungen",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )

                // App limit input
                OutlinedTextField(
                    value = limitInput,
                    onValueChange = { limitInput = it },
                    label = { Text("Standard-Zeitlimit pro App (Minuten)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                // Warning lead time input
                OutlinedTextField(
                    value = warningInput,
                    onValueChange = { warningInput = it },
                    label = { Text("Warnzeit vor dem Schließen (Sekunden)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                // Cooldown minutes input
                OutlinedTextField(
                    value = cooldownInput,
                    onValueChange = { cooldownInput = it },
                    label = { Text("Cooldown-Dauer (Minuten)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    onClick = {
                        val limit = limitInput.toFloatOrNull() ?: 5.0f
                        val warning = warningInput.toIntOrNull() ?: 30
                        val cooldown = cooldownInput.toFloatOrNull() ?: 10.0f
                        val limitMs = (limit * 60 * 1000).toLong()

                        val editor = prefs.edit()
                            .putFloat("limit_minutes", limit)
                            .putInt("warning_seconds", warning)
                            .putFloat("cooldown_minutes", cooldown)

                        // Reset all active budgets to the new limit
                        selectedPackages.forEach { pkg ->
                            editor.putLong("remaining_budget_ms_$pkg", limitMs)
                            editor.putLong("last_exit_time_$pkg", SystemClock.elapsedRealtime())
                        }
                        editor.apply()
                            
                        uiTickTrigger = SystemClock.elapsedRealtime() // Force UI update
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Einstellungen speichern")
                }
            }
        }
    }
}

@Composable
fun PermissionRow(
    title: String,
    subtitle: String,
    isGranted: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        if (isGranted) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Erlaubt",
                tint = Color(0xFF4CAF50), // Modern Green
                modifier = Modifier.size(28.dp)
            )
        } else {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "Benötigt",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

private fun getInstalledApps(context: Context): List<AppItem> {
    val pm = context.packageManager
    val apps = pm.getInstalledApplications(0)
    return apps.filter { appInfo ->
        val launchIntent = pm.getLaunchIntentForPackage(appInfo.packageName)
        launchIntent != null && appInfo.packageName != context.packageName
    }.map { appInfo ->
        AppItem(
            name = appInfo.loadLabel(pm).toString(),
            packageName = appInfo.packageName
        )
    }.sortedBy { it.name }
}

private fun isAccessibilityServiceEnabled(context: Context, service: Class<out AccessibilityService>): Boolean {
    val expectedComponentName = ComponentName(context, service)
    val enabledServicesSetting = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    val colonSplitter = TextUtils.SimpleStringSplitter(':')
    colonSplitter.setString(enabledServicesSetting)
    while (colonSplitter.hasNext()) {
        val componentNameString = colonSplitter.next()
        val enabledService = ComponentName.unflattenFromString(componentNameString)
        if (enabledService != null && enabledService == expectedComponentName) {
            return true
        }
    }
    return false
}
