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
import androidx.compose.foundation.layout.Column
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
import br.unioeste.pulselab.wear.model.DataSource
import br.unioeste.pulselab.wear.model.HealthMetric
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
        val source by viewModel.source.collectAsState()
        HomeScreenContent(
            uiState = uiState,
            isPhoneConnected = isPhoneConnected,
            source = source,
            onStart = { viewModel.startSession() },
            onStop  = { viewModel.stopSession() },
            onCycleSource = { viewModel.cycleDataSource() }
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

// Resolve a metric to its current display value from the Monitoring state.
private fun HomeUiState.Monitoring.valueFor(metric: HealthMetric): String? = when (metric) {
    HealthMetric.HEART_RATE -> bpm?.let { "%.0f".format(it) }
    HealthMetric.HRV -> hrv?.toString()
    HealthMetric.SPO2 -> spo2?.toString()
    HealthMetric.SKIN_TEMP -> skinTemp?.let { "%.1f".format(it) }
    HealthMetric.STRESS -> stressIndex?.toString()
}

// ─── STATELESS CONTENT ──────────────────────────────────────────────────────
@Composable
fun HomeScreenContent(
    uiState: HomeUiState,
    isPhoneConnected: Boolean = false,
    source: DataSource = DataSource.LIVE,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCycleSource: () -> Unit = {}
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

                // Source selector — visible in every state for quick debug switching.
                item {
                    Button(onClick = onCycleSource, modifier = Modifier.fillMaxWidth()) {
                        Text("Source: ${source.label}")
                    }
                }

                when (val state = uiState) {

                    is HomeUiState.Idle -> {
                        item {
                            Text(
                                text = "Ready — press Start",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                    is HomeUiState.Monitoring -> {
                        // Every metric, every time — value or a "where it comes from" note.
                        item {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                HealthMetric.entries.forEach { metric ->
                                    val value = state.valueFor(metric)
                                    Text(
                                        text = "${metric.label}: ${value ?: "—"} ${metric.unit}",
                                        style = MaterialTheme.typography.titleSmall,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    if (value == null) {
                                        Text(
                                            text = "↳ ${metric.provider}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        // Debug block.
                        item {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "source=${state.source.badge}  sent=${state.readingsSent}",
                                    style = MaterialTheme.typography.labelSmall
                                )
                                Text(
                                    text = "phoneReachable=${state.phoneReachable}",
                                    style = MaterialTheme.typography.labelSmall
                                )
                                Text(
                                    text = "lastUpdate=${state.lastUpdate ?: "—"}",
                                    style = MaterialTheme.typography.labelSmall
                                )
                                if (state.source == DataSource.BACKGROUND) {
                                    Text(
                                        text = "● streaming in background",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF4CAF50)
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
fun PreviewMonitoringLive() {
    HomeScreenContent(
        uiState = HomeUiState.Monitoring(
            bpm = 78.0, hrv = null, spo2 = null, stressIndex = null, skinTemp = null,
            sessionId = "preview-live", readingsSent = 12,
            source = DataSource.LIVE, phoneReachable = true, lastUpdate = 1_700_000_000_000L
        ),
        source = DataSource.LIVE,
        onStart = {},
        onStop = {}
    )
}

@WearPreviewDevices
@Composable
fun PreviewMonitoringSimulated() {
    HomeScreenContent(
        uiState = HomeUiState.Monitoring(
            bpm = 72.0, hrv = 48, spo2 = 98, stressIndex = 25, skinTemp = 32.4,
            sessionId = "preview-sim", readingsSent = 7,
            source = DataSource.SIMULATED, phoneReachable = true, lastUpdate = 1_700_000_000_000L
        ),
        source = DataSource.SIMULATED,
        onStart = {},
        onStop = {}
    )
}

@WearPreviewDevices
@Composable
fun PreviewMonitoringBackgroundNoPhone() {
    HomeScreenContent(
        uiState = HomeUiState.Monitoring(
            bpm = 71.0, hrv = null, spo2 = null, stressIndex = null, skinTemp = null,
            sessionId = "preview-bg", readingsSent = 3,
            source = DataSource.BACKGROUND, phoneReachable = false
        ),
        source = DataSource.BACKGROUND,
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
