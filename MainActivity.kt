package com.example.trackernotifier

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TrackerNotifierScreen()
                }
            }
        }
    }
}

@Composable
fun TrackerNotifierScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("whitelist_prefs", Context.MODE_PRIVATE) }
    val statusText by BleTrackerManager.connectionStatus.collectAsState()
    var deviceFilter by remember { mutableStateOf(prefs.getString("device_filter", "") ?: "") }
    var selectedPackages by remember {
        mutableStateOf(prefs.getStringSet("allowed_apps", emptySet()) ?: emptySet())
    }
    var testPattern by remember { mutableStateOf(VibrationPattern.DOUBLE) }
    var testPatternMenuExpanded by remember { mutableStateOf(false) }

    val installedApps = remember {
        val pm = context.packageManager
        pm.getInstalledApplications(0)
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .map { it.packageName to pm.getApplicationLabel(it).toString() }
            .sortedBy { it.second.lowercase() }
    }

    var appPatterns by remember {
        mutableStateOf(
            installedApps.associate { (pkg, _) ->
                pkg to VibrationPattern.fromName(prefs.getString("vibe_pattern_$pkg", null))
            }
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val btGranted = perms[Manifest.permission.BLUETOOTH_SCAN] == true &&
            perms[Manifest.permission.BLUETOOTH_CONNECT] == true
        if (btGranted) {
            TrackerForegroundService.start(context, deviceFilter.trim())
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Status: $statusText", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = deviceFilter,
            onValueChange = {
                deviceFilter = it
                prefs.edit().putString("device_filter", it).apply()
            },
            label = { Text("Tracker Bluetooth Name (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }) {
                Text("1. Notif Access")
            }
            Button(onClick = {
                val perms = buildList {
                    add(Manifest.permission.BLUETOOTH_SCAN)
                    add(Manifest.permission.BLUETOOTH_CONNECT)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }.toTypedArray()
                permissionLauncher.launch(perms)
            }) {
                Text("2. Start Service")
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = {
                BleTrackerManager.sendNotificationAlert(
                    "Test",
                    "Pixel 9 Pro",
                    "Tracker connected!",
                    testPattern
                )
            }) {
                Text("Test Buzz (${testPattern.label})")
            }

            Box {
                TextButton(onClick = { testPatternMenuExpanded = true }) {
                    Text("Pattern")
                }
                DropdownMenu(
                    expanded = testPatternMenuExpanded,
                    onDismissRequest = { testPatternMenuExpanded = false }
                ) {
                    VibrationPattern.entries.forEach { pattern ->
                        DropdownMenuItem(
                            text = { Text(pattern.label) },
                            onClick = {
                                testPattern = pattern
                                testPatternMenuExpanded = false
                            }
                        )
                    }
                }
            }

            OutlinedButton(onClick = {
                TrackerForegroundService.stop(context)
            }) {
                Text("Stop")
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Allowed Apps & Vibration Pattern:", style = MaterialTheme.typography.titleSmall)

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(installedApps) { (pkg, label) ->
                val isChecked = pkg in selectedPackages
                val currentPattern = appPatterns[pkg] ?: VibrationPattern.SINGLE
                var menuExpanded by remember(pkg) { mutableStateOf(false) }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = isChecked,
                        onCheckedChange = { checked ->
                            val updated = selectedPackages.toMutableSet().apply {
                                if (checked) add(pkg) else remove(pkg)
                            }
                            selectedPackages = updated
                            prefs.edit().putStringSet("allowed_apps", updated).apply()
                        }
                    )
                    Text(
                        label,
                        modifier = Modifier.weight(1f).padding(start = 8.dp)
                    )
                    if (isChecked) {
                        Box {
                            TextButton(onClick = { menuExpanded = true }) {
                                Text(currentPattern.label)
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false }
                            ) {
                                VibrationPattern.entries.forEach { pattern ->
                                    DropdownMenuItem(
                                        text = { Text(pattern.label) },
                                        onClick = {
                                            appPatterns = appPatterns + (pkg to pattern)
                                            prefs.edit()
                                                .putString("vibe_pattern_$pkg", pattern.name)
                                                .apply()
                                            menuExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
