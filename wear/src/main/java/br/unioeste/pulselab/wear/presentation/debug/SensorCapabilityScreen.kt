package br.unioeste.pulselab.wear.presentation.debug

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import br.unioeste.pulselab.wear.data.health.debug.DebugMetricState
import br.unioeste.pulselab.wear.data.health.debug.DebugMetricStatus

// SensorCapabilityScreen is split the same way as HomeScreen:
//   SensorCapabilityScreen        — knows about the ViewModel (stateful, not previewable)
//   SensorCapabilityScreenContent — only knows about data + callbacks (stateless, previewable)

@Composable
fun SensorCapabilityScreen(viewModel: SensorCapabilityViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    SensorCapabilityScreenContent(
        metrics = uiState,
        onTrigger = { metricId -> viewModel.onTrigger(metricId) }
    )
}

private fun DebugMetricStatus.label(): String = when (this) {
    DebugMetricStatus.UNAVAILABLE -> "Unavailable"
    DebugMetricStatus.READY -> "Ready"
    DebugMetricStatus.READING -> "Reading..."
    DebugMetricStatus.DONE -> "Done"
    DebugMetricStatus.ERROR -> "Error"
}

private fun DebugMetricStatus.badgeColor(): Color = when (this) {
    DebugMetricStatus.UNAVAILABLE -> Color.Gray
    DebugMetricStatus.READY -> Color(0xFF9E9E9E)
    DebugMetricStatus.READING -> Color(0xFFFFC107)
    DebugMetricStatus.DONE -> Color(0xFF4CAF50)
    DebugMetricStatus.ERROR -> Color(0xFFF44336)
}

// ─── STATELESS CONTENT ──────────────────────────────────────────────────────
@Composable
fun SensorCapabilityScreenContent(
    metrics: List<DebugMetricState>,
    onTrigger: (String) -> Unit = {}
) {
    AppScaffold {
        val listState = rememberTransformingLazyColumnState()

        ScreenScaffold(scrollState = listState) { contentPadding ->
            TransformingLazyColumn(contentPadding = contentPadding, state = listState) {

                item {
                    ListHeader(modifier = Modifier.fillMaxWidth()) {
                        Text("Sensor Capabilities")
                    }
                }

                items(metrics) { metric ->
                    MetricRow(metric = metric, onTrigger = onTrigger)
                }
            }
        }
    }
}

@Composable
private fun MetricRow(metric: DebugMetricState, onTrigger: (String) -> Unit) {
    val isUnavailable = metric.status == DebugMetricStatus.UNAVAILABLE
    val contentAlpha = if (isUnavailable) 0.4f else 1f

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = metric.metricName,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha)
            )
            Text(
                text = "  ${metric.status.label()}",
                style = MaterialTheme.typography.labelSmall,
                color = metric.status.badgeColor().copy(alpha = contentAlpha)
            )
        }
        Text(
            text = if (metric.values.isEmpty()) "—" else metric.values.joinToString(" "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha)
        )
        if (!isUnavailable && metric.isOnDemand) {
            Button(
                onClick = { onTrigger(metric.metricId) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (metric.status == DebugMetricStatus.READING) "Reading..." else "Trigger")
            }
        }
    }
}

// ─── PREVIEWS ───────────────────────────────────────────────────────────────

private fun previewMetric(
    status: DebugMetricStatus,
    isOnDemand: Boolean = true,
    values: List<String> = emptyList()
) = DebugMetricState(
    metricId = "ecg",
    metricName = "ECG",
    status = status,
    values = values,
    isOnDemand = isOnDemand
)

@WearPreviewDevices
@Composable
fun PreviewUnavailable() {
    SensorCapabilityScreenContent(metrics = listOf(previewMetric(DebugMetricStatus.UNAVAILABLE)))
}

@WearPreviewDevices
@Composable
fun PreviewReady() {
    SensorCapabilityScreenContent(metrics = listOf(previewMetric(DebugMetricStatus.READY)))
}

@WearPreviewDevices
@Composable
fun PreviewReading() {
    SensorCapabilityScreenContent(metrics = listOf(previewMetric(DebugMetricStatus.READING)))
}

@WearPreviewDevices
@Composable
fun PreviewDone() {
    SensorCapabilityScreenContent(
        metrics = listOf(
            previewMetric(DebugMetricStatus.DONE, values = listOf("0.4 mV", "lead off=0"))
        )
    )
}

@WearPreviewDevices
@Composable
fun PreviewError() {
    SensorCapabilityScreenContent(metrics = listOf(previewMetric(DebugMetricStatus.ERROR)))
}
