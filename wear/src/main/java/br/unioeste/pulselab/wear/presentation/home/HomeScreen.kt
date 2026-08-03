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
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import br.unioeste.pulselab.wear.model.HealthMetric
import br.unioeste.pulselab.wear.model.SensorBundle
import br.unioeste.pulselab.wear.presentation.theme.PulseLabColors

// HomeScreen is split into two layers:
//   HomeScreen        — knows about the ViewModel (stateful, not previewable)
//   HomeScreenContent — only knows about data + callbacks (stateless, previewable)
//
// Body composition (BIA) is deliberately not reachable from here: the Galaxy Watch
// Ultra advertises BIA_ON_DEMAND but rejects the tracker, so the flow is parked
// until that is resolved. BiaScreen/BiaViewModel/BiaMeasurementManager are kept in
// the tree, unreferenced, to be wired back once it works.

private val REQUIRED_PERMISSIONS = arrayOf(
    Manifest.permission.BODY_SENSORS,
    Manifest.permission.ACTIVITY_RECOGNITION
)

@Composable
fun HomeScreen(viewModel: HomeViewModel = viewModel()) {
    val context = LocalContext.current

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.BODY_SENSORS)
                == PackageManager.PERMISSION_GRANTED
        )
    }
    var isPermanentlyDenied by remember { mutableStateOf(false) }
    var permissionRequested by remember { mutableStateOf(false) }

    // ACTIVITY_RECOGNITION is requested alongside BODY_SENSORS because the Samsung
    // accelerometer tracker needs it, but the screen is NOT gated on it: without
    // it the motion gate simply never flags a window, and the other five metrics
    // are unaffected.
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results[Manifest.permission.BODY_SENSORS] == true
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
        // Relay behavior: start streaming as soon as we can, no tap required.
        LaunchedEffect(Unit) { viewModel.startSession() }
        HomeScreenContent(
            uiState = uiState,
            isPhoneConnected = isPhoneConnected,
            onStart = { viewModel.startSession() },
            onStop = { viewModel.stopSession() }
        )
    } else {
        BodySensorsRationaleScreen(
            isPermanentlyDenied = isPermanentlyDenied,
            onGrantPermission = { launcher.launch(REQUIRED_PERMISSIONS) },
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
                        text = "O app precisa acessar os sensores do relógio para monitorar sua biometria.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                item {
                    if (isPermanentlyDenied) {
                        Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                            Text("Abrir Configurações")
                        }
                    } else {
                        Button(onClick = onGrantPermission, modifier = Modifier.fillMaxWidth()) {
                            Text("Permitir acesso")
                        }
                    }
                }
            }
        }
    }
}

/** Formats a metric for its orb, or null when that sensor has no value yet. */
private fun SensorBundle?.orbValue(metric: HealthMetric): String? = when (metric) {
    HealthMetric.HEART_RATE -> this?.bpm?.toString()
    HealthMetric.HRV -> this?.hrv?.toString()
    HealthMetric.STRESS_INDEX -> this?.baevskySI?.let { "%.0f".format(it) }
    HealthMetric.SPO2 -> this?.spo2?.toString()
    HealthMetric.SKIN_TEMP -> this?.skinTemp?.let { "%.1f".format(it) }
}

// ─── STATELESS CONTENT ──────────────────────────────────────────────────────

@Composable
fun HomeScreenContent(
    uiState: HomeUiState,
    isPhoneConnected: Boolean = false,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    AppScaffold {
        val listState = rememberTransformingLazyColumnState()

        ScreenScaffold(
            scrollState = listState,
            edgeButton = {
                when (uiState) {
                    is HomeUiState.Idle, is HomeUiState.Error ->
                        EdgeButton(onClick = onStart) { Text("Iniciar") }
                    is HomeUiState.Monitoring ->
                        EdgeButton(onClick = onStop) { Text("Parar") }
                }
            }
        ) { contentPadding ->
            TransformingLazyColumn(contentPadding = contentPadding, state = listState) {

                item {
                    Text(
                        text = if (isPhoneConnected) "● Celular conectado" else "○ Celular desconectado",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isPhoneConnected) PulseLabColors.Green else PulseLabColors.TextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                    )
                }

                when (val state = uiState) {

                    is HomeUiState.Idle -> item {
                        Text(
                            text = "Monitoramento parado.",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    is HomeUiState.Monitoring -> {
                        // Two orbs per row: the widest pair that stays legible
                        // inside a round display's usable width.
                        HealthMetric.entries.chunked(2).forEach { pair ->
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                    horizontalArrangement = Arrangement.spacedBy(
                                        10.dp, Alignment.CenterHorizontally
                                    )
                                ) {
                                    pair.forEach { metric ->
                                        MetricOrb(
                                            metric = metric,
                                            value = state.reading.orbValue(metric)
                                        )
                                    }
                                }
                            }
                        }

                        // Only shown while it is true — a permanent "parado" line
                        // would be noise on a screen meant to stay quiet.
                        if (state.reading?.motionFlag == true) {
                            item {
                                Text(
                                    text = "movimento detectado",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = PulseLabColors.Warning,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                                )
                            }
                        }
                    }

                    is HomeUiState.Error -> {
                        item {
                            Text(
                                text = "Algo deu errado",
                                style = MaterialTheme.typography.titleMedium,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        item {
                            Text(
                                text = state.message,
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─── PREVIEWS ───────────────────────────────────────────────────────────────

@WearPreviewDevices
@Composable
fun PreviewMonitoring() {
    HomeScreenContent(
        uiState = HomeUiState.Monitoring(
            reading = SensorBundle(
                bpm = 72, hrv = 48, spo2 = 98, skinTemp = 32.4,
                baevskySI = 118.0, edaTonic = 4.82, edaScrPerMin = 3,
                motionFlag = false, timestamp = 1_700_000_000_000L,
                sequenceNumber = 0L,
                spo2MeasuredAt = 1_700_000_000_000L,
                skinTempMeasuredAt = 1_700_000_000_000L,
                providerId = "samsung-shs-sdk"
            ),
            phoneReachable = true
        ),
        isPhoneConnected = true,
        onStart = {},
        onStop = {}
    )
}

@WearPreviewDevices
@Composable
fun PreviewWaitingForSensors() {
    HomeScreenContent(
        uiState = HomeUiState.Monitoring(reading = null),
        onStart = {},
        onStop = {}
    )
}

@WearPreviewDevices
@Composable
fun PreviewIdle() {
    HomeScreenContent(uiState = HomeUiState.Idle, onStart = {}, onStop = {})
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
