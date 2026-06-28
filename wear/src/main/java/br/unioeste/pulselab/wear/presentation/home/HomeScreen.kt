package br.unioeste.pulselab.wear.presentation.home

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import br.unioeste.pulselab.wear.BuildConfig
import br.unioeste.pulselab.wear.data.health.HeartRateRepository
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices

// HomeScreen is split into two layers:
//   HomeScreen        — knows about the ViewModel (stateful, not previewable)
//   HomeScreenContent — only knows about data + callbacks (stateless, previewable)

@Composable
fun HomeScreen(viewModel: HomeViewModel = viewModel()) {
    val context = LocalContext.current

    var hasPermission by remember {
        mutableStateOf(
            !HeartRateRepository.requiresSensorPermission ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BODY_SENSORS)
                == PackageManager.PERMISSION_GRANTED
        )
    }
    var isPermanentlyDenied by remember { mutableStateOf(false) }
    var permissionRequested by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            hasPermission = true
        } else {
            val activity = context as? Activity
            // On some Wear OS devices the system dialog never shows; shouldShowRequestPermissionRationale
            // always returns false. After the first tap (permissionRequested=true), treat it as
            // permanently denied and route directly to Settings.
            isPermanentlyDenied = activity != null &&
                (!ActivityCompat.shouldShowRequestPermissionRationale(
                    activity, Manifest.permission.BODY_SENSORS
                ) || permissionRequested)
        }
        permissionRequested = true
    }

    if (hasPermission) {
        val uiState by viewModel.uiState.collectAsState()
        val isPhoneConnected by viewModel.isPhoneConnected.collectAsState()
        val isSimulated by viewModel.isSimulated.collectAsState()
        HomeScreenContent(
            uiState = uiState,
            isPhoneConnected = isPhoneConnected,
            isSimulated = isSimulated,
            onStart = { viewModel.startSession() },
            onStop  = { viewModel.stopSession() },
            onToggleDataSource = { viewModel.toggleDataSource() }
        )
    } else {
        BodySensorsRationaleScreen(
            isPermanentlyDenied = isPermanentlyDenied,
            onGrantPermission = { launcher.launch(Manifest.permission.BODY_SENSORS) },
            onOpenSettings = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                )
            }
        )
    }
}

@Composable
private fun BodySensorsRationaleScreen(
    isPermanentlyDenied: Boolean,
    onGrantPermission: () -> Unit,
    onOpenSettings: () -> Unit
) {
    AppScaffold {
        val listState = rememberTransformingLazyColumnState()
        ScreenScaffold(scrollState = listState) { contentPadding ->
            TransformingLazyColumn(contentPadding = contentPadding, state = listState) {
                item {
                    Text(
                        text = "Heart Rate Permission",
                        style = MaterialTheme.typography.titleLarge
                    )
                }
                item {
                    Text(
                        text = "This app needs access to your heart rate sensor to monitor your BPM.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                item {
                    if (isPermanentlyDenied) {
                        Button(
                            onClick = onOpenSettings,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Open Settings")
                        }
                    } else {
                        Button(
                            onClick = onGrantPermission,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Grant Permission")
                        }
                    }
                }
            }
        }
    }
}

// ─── STATELESS CONTENT ──────────────────────────────────────────────────────
@Composable
fun HomeScreenContent(
    uiState: HomeUiState,
    isPhoneConnected: Boolean = false,
    isSimulated: Boolean = false,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onToggleDataSource: () -> Unit = {}
) {
    AppScaffold {
        val listState = rememberTransformingLazyColumnState()

        ScreenScaffold(
            scrollState = listState,
            edgeButton = {
                when (uiState) {
                    is HomeUiState.Idle, is HomeUiState.Error -> {
                        EdgeButton(onClick = onStart) { Text("Start") }
                    }
                    is HomeUiState.Monitoring -> {
                        EdgeButton(onClick = onStop) { Text("Stop") }
                    }
                }
            }
        ) { contentPadding ->
            TransformingLazyColumn(contentPadding = contentPadding, state = listState) {

                item {
                    ListHeader(modifier = Modifier.fillMaxWidth()) {
                        Text("PulseLab Wear")
                    }
                }

                when (val state = uiState) {

                    is HomeUiState.Idle -> {
                        item {
                            Text(
                                text = "Ready",
                                style = MaterialTheme.typography.titleLarge
                            )
                        }
                        item {
                            Text(
                                text = "Press Start to begin",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        item {
                            Button(
                                onClick = onToggleDataSource,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (isSimulated) "Source: Fake" else "Source: Real")
                            }
                        }
                    }

                    is HomeUiState.Monitoring -> {
                        item {
                            Text(
                                text = buildString {
                                    append("HR: ")
                                    append(state.bpm?.let { "%.0f".format(it) } ?: "...")
                                    append(" BPM")
                                    if (state.isSimulated) append(" (SIM)")
                                },
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        item {
                            Text(
                                text = "HRV: ${state.hrv?.toString() ?: "..."} ms",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        item {
                            Text(
                                text = "SpO2: ${state.spo2?.toString() ?: "..."} %",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        item {
                            Text(
                                text = "Stress: ${state.stressIndex?.toString() ?: "..."} / 100",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        item {
                            Button(
                                onClick = onToggleDataSource,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (state.isSimulated) "Source: Fake" else "Source: Real")
                            }
                        }
                        if (state.isBackground) {
                            item {
                                Text(
                                    text = "● Streaming in background",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF4CAF50)
                                )
                            }
                        }
                        item {
                            Text(
                                text = "Sent: ${state.readingsSent}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (state.phoneReachable != null) {
                            item {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.PhoneAndroid,
                                        contentDescription = "Phone connection",
                                        tint = if (state.phoneReachable)
                                            MaterialTheme.colorScheme.primary
                                        else
                                            MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }

                    is HomeUiState.Error -> {
                        item {
                            Text(text = "Error", style = MaterialTheme.typography.titleLarge)
                        }
                        item {
                            Text(text = state.message, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Text(
                            text = if (isPhoneConnected) "Phone: Connected" else "Phone: Disconnected",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isPhoneConnected) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
                        )
                    }
                }

                item {
                    Text(
                        text = "v${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

// ─── PREVIEWS ───────────────────────────────────────────────────────────────

@WearPreviewDevices
@Composable
fun PreviewIdle() {
    HomeScreenContent(uiState = HomeUiState.Idle, onStart = {}, onStop = {})
}

@WearPreviewDevices
@Composable
fun PreviewIdleSimulated() {
    HomeScreenContent(uiState = HomeUiState.Idle, isSimulated = true, onStart = {}, onStop = {})
}

@WearPreviewDevices
@Composable
fun PreviewMonitoring() {
    HomeScreenContent(
        uiState = HomeUiState.Monitoring(
            bpm = 78.0, hrv = null, spo2 = null, stressIndex = null,
            sessionId = "preview", readingsSent = 12, isSimulated = false,
            isBackground = true
        ),
        onStart = {},
        onStop = {}
    )
}

@WearPreviewDevices
@Composable
fun PreviewMonitoringSimulated() {
    HomeScreenContent(
        uiState = HomeUiState.Monitoring(
            bpm = 72.0, hrv = 48, spo2 = 98, stressIndex = 25,
            sessionId = "preview-sim", readingsSent = 7, isSimulated = true
        ),
        isSimulated = true,
        onStart = {},
        onStop = {}
    )
}

@WearPreviewDevices
@Composable
fun PreviewMonitoringNoPhone() {
    HomeScreenContent(
        uiState = HomeUiState.Monitoring(
            bpm = 72.0, hrv = 42, spo2 = 97, stressIndex = 55,
            sessionId = "preview-no-phone", readingsSent = 3,
            phoneReachable = false
        ),
        onStart = {},
        onStop = {}
    )
}

@WearPreviewDevices
@Composable
fun PreviewError() {
    HomeScreenContent(
        uiState = HomeUiState.Error("Could not reach server"),
        onStart = {},
        onStop = {}
    )
}
