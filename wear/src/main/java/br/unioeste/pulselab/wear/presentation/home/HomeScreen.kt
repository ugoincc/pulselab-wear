package br.unioeste.pulselab.wear.presentation.home

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import br.unioeste.pulselab.wear.BuildConfig
import br.unioeste.pulselab.wear.data.health.HeartRateRepository
import br.unioeste.pulselab.wear.model.DataSource
import br.unioeste.pulselab.wear.model.HealthMetric
import br.unioeste.pulselab.wear.presentation.bia.BiaScreen
import br.unioeste.pulselab.wear.presentation.debug.SensorCapabilityScreen
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices

// HomeScreen is split into two layers:
//   HomeScreen        — knows about the ViewModel (stateful, not previewable)
//   HomeScreenContent — only knows about data + callbacks (stateless, previewable)

private enum class SubScreen { NONE, BIA, DEBUG }

@Composable
fun HomeScreen(viewModel: HomeViewModel = viewModel()) {
    var subScreen by remember { mutableStateOf(SubScreen.NONE) }

    when (subScreen) {
        SubScreen.BIA -> {
            BackHandler { subScreen = SubScreen.NONE }
            BiaScreen()
            return
        }
        SubScreen.DEBUG -> {
            BackHandler { subScreen = SubScreen.NONE }
            SensorCapabilityScreen()
            return
        }
        SubScreen.NONE -> Unit
    }

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
            onCycleSource = { viewModel.cycleDataSource() },
            onOpenBia = { subScreen = SubScreen.BIA },
            onOpenDebug = { subScreen = SubScreen.DEBUG }
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
                        text = "Sensores corporais",
                        style = MaterialTheme.typography.titleLarge
                    )
                }
                item {
                    Text(
                        text = "O app precisa acessar os sensores do relógio para monitorar sua frequência cardíaca.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                item {
                    if (isPermanentlyDenied) {
                        Button(
                            onClick = onOpenSettings,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Abrir Configurações")
                        }
                    } else {
                        Button(
                            onClick = onGrantPermission,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Permitir acesso")
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

private val METRIC_NAMES = mapOf(
    HealthMetric.HEART_RATE to "Frequência cardíaca",
    HealthMetric.HRV to "Variabilidade (HRV)",
    HealthMetric.SPO2 to "Oxigenação (SpO2)",
    HealthMetric.SKIN_TEMP to "Temperatura da pele",
    HealthMetric.STRESS to "Estresse",
)

// ─── STATELESS CONTENT ──────────────────────────────────────────────────────
@Composable
fun HomeScreenContent(
    uiState: HomeUiState,
    isPhoneConnected: Boolean = false,
    source: DataSource = DataSource.SENSOR,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCycleSource: () -> Unit = {},
    onOpenBia: () -> Unit = {},
    showDebugEntry: Boolean = BuildConfig.DEBUG,
    onOpenDebug: () -> Unit = {}
) {
    AppScaffold {
        val listState = rememberTransformingLazyColumnState()

        ScreenScaffold(
            scrollState = listState,
            edgeButton = {
                when (uiState) {
                    is HomeUiState.Idle, is HomeUiState.Error -> {
                        EdgeButton(onClick = onStart) { Text("Iniciar") }
                    }
                    is HomeUiState.Monitoring -> {
                        EdgeButton(onClick = onStop) { Text("Parar") }
                    }
                }
            }
        ) { contentPadding ->
            TransformingLazyColumn(contentPadding = contentPadding, state = listState) {

                item {
                    ListHeader(modifier = Modifier.fillMaxWidth()) {
                        Text("PulseLab")
                    }
                }

                // Phone connection status, always visible.
                item {
                    Text(
                        text = if (isPhoneConnected) "● Celular conectado" else "○ Celular desconectado",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isPhoneConnected) Color(0xFF4CAF50)
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                when (val state = uiState) {

                    is HomeUiState.Idle -> {
                        item {
                            Text(
                                text = "Toque em Iniciar para monitorar seus sinais em tempo real.",
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            )
                        }
                    }

                    is HomeUiState.Monitoring -> {
                        HealthMetric.entries.forEach { metric ->
                            item {
                                val value = state.valueFor(metric)
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = METRIC_NAMES.getValue(metric),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = value?.let { "$it ${metric.unit}" } ?: "—",
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                }
                            }
                        }

                        item {
                            Text(
                                text = "A coleta continua com a tela desligada.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                            )
                        }
                    }

                    is HomeUiState.Error -> {
                        item {
                            Text(text = "Algo deu errado", style = MaterialTheme.typography.titleLarge)
                        }
                        item {
                            Text(text = state.message, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                // Body composition (BIA) — on-demand guided measurement.
                item {
                    OutlinedButton(onClick = onOpenBia, modifier = Modifier.fillMaxWidth()) {
                        Text("Composição corporal")
                    }
                }

                if (showDebugEntry) {
                    item {
                        Button(onClick = onCycleSource, modifier = Modifier.fillMaxWidth()) {
                            Text("Fonte: ${source.label}")
                        }
                    }
                    item {
                        Button(onClick = onOpenDebug, modifier = Modifier.fillMaxWidth()) {
                            Text("Debug: capacidades")
                        }
                    }
                    if (uiState is HomeUiState.Monitoring) {
                        item {
                            Text(
                                text = "src=${uiState.source.badge} sent=${uiState.readingsSent} phone=${uiState.phoneReachable}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
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
fun PreviewMonitoringSensor() {
    HomeScreenContent(
        uiState = HomeUiState.Monitoring(
            bpm = 72.0, hrv = 48, spo2 = 98, stressIndex = null, skinTemp = 32.4,
            sessionId = "preview-sensor", readingsSent = 41,
            source = DataSource.SENSOR, phoneReachable = true, lastUpdate = 1_700_000_000_000L
        ),
        isPhoneConnected = true,
        source = DataSource.SENSOR,
        onStart = {},
        onStop = {}
    )
}

@WearPreviewDevices
@Composable
fun PreviewMonitoringNoPhone() {
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
        uiState = HomeUiState.Error("Não foi possível conectar aos sensores"),
        onStart = {},
        onStop = {}
    )
}

@WearPreviewDevices
@Composable
fun PreviewProductionNoDebug() {
    HomeScreenContent(
        uiState = HomeUiState.Idle,
        onStart = {},
        onStop = {},
        showDebugEntry = false
    )
}
