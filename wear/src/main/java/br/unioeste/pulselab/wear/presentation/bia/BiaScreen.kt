package br.unioeste.pulselab.wear.presentation.bia

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import br.unioeste.pulselab.wear.data.health.BiaResult
import br.unioeste.pulselab.wear.presentation.theme.PulseLabWearTheme
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices

// BiaScreen is split into two layers (same pattern as HomeScreen):
//   BiaScreen        — knows about the ViewModel (stateful)
//   BiaScreenContent — data + callbacks only (stateless, previewable)

@Composable
fun BiaScreen(viewModel: BiaViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    BiaScreenContent(
        uiState = uiState,
        onStart = { viewModel.startMeasurement() },
        onRetry = { viewModel.reset() }
    )
}

@Composable
fun BiaScreenContent(
    uiState: BiaUiState,
    onStart: () -> Unit,
    onRetry: () -> Unit
) {
    AppScaffold {
        when (val state = uiState) {
            is BiaUiState.Instructions -> InstructionsStep(onStart)
            is BiaUiState.Measuring -> MeasuringStep(state)
            is BiaUiState.Done -> ResultStep(state.result, onRetry)
            is BiaUiState.Failed -> FailedStep(state.reason, onRetry)
        }
    }
}

// ─── Passo 1: instruções ────────────────────────────────────────────────────

private val INSTRUCTIONS = listOf(
    "1. Ajuste bem a pulseira, relógio firme no pulso.",
    "2. Levante os braços à frente do corpo, sem encostar os cotovelos no tronco.",
    "3. Apoie dois dedos da outra mão nos dois botões laterais.",
    "4. Os dedos não podem se tocar, nem tocar a pele do braço do relógio.",
    "5. Fique parado até completar (~15 s).",
)

@Composable
private fun InstructionsStep(onStart: () -> Unit) {
    val listState = rememberTransformingLazyColumnState()
    ScreenScaffold(
        scrollState = listState,
        edgeButton = { EdgeButton(onClick = onStart) { Text("Iniciar medição") } }
    ) { contentPadding ->
        TransformingLazyColumn(contentPadding = contentPadding, state = listState) {
            item {
                ListHeader(modifier = Modifier.fillMaxWidth()) {
                    Text("Composição corporal")
                }
            }
            item {
                Text(
                    text = "Medição por bioimpedância (BIA)",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            INSTRUCTIONS.forEach { line ->
                item {
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    )
                }
            }
        }
    }
}

// ─── Passo 2: medindo ───────────────────────────────────────────────────────

@Composable
private fun MeasuringStep(state: BiaUiState.Measuring) {
    ScreenScaffold {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { state.progress },
                modifier = Modifier.size(80.dp)
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${(state.progress * 100).toInt()}%",
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = if (state.contactLost) "Ajuste os dedos nos botões" else "Fique parado",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.contactLost) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

// ─── Passo 3: resultado ─────────────────────────────────────────────────────

private fun Float?.fmt(unit: String, decimals: Int = 1): String =
    this?.let { "%.${decimals}f $unit".format(it) } ?: "—"

@Composable
private fun ResultStep(result: BiaResult, onRetry: () -> Unit) {
    val listState = rememberTransformingLazyColumnState()
    ScreenScaffold(
        scrollState = listState,
        edgeButton = { EdgeButton(onClick = onRetry) { Text("Nova medição") } }
    ) { contentPadding ->
        TransformingLazyColumn(contentPadding = contentPadding, state = listState) {
            item {
                ListHeader(modifier = Modifier.fillMaxWidth()) { Text("Resultado") }
            }
            val rows = listOf(
                "Gordura corporal" to result.bodyFatRatio.fmt("%"),
                "Massa de gordura" to result.bodyFatMass.fmt("kg"),
                "Músculo esquelético" to result.skeletalMuscleRatio.fmt("%"),
                "Massa muscular" to result.skeletalMuscleMass.fmt("kg"),
                "Água corporal" to result.totalBodyWater.fmt("L"),
                "Taxa metabólica basal" to result.basalMetabolicRate.fmt("kcal", 0),
                "Massa livre de gordura" to result.fatFreeMass.fmt("kg"),
                "Impedância" to result.impedance.fmt("Ω", 0),
            )
            rows.forEach { (label, value) ->
                item {
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(text = value, style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
        }
    }
}

// ─── Falha ──────────────────────────────────────────────────────────────────

@Composable
private fun FailedStep(reason: String, onRetry: () -> Unit) {
    val listState = rememberTransformingLazyColumnState()
    ScreenScaffold(
        scrollState = listState,
        edgeButton = { EdgeButton(onClick = onRetry) { Text("Tentar de novo") } }
    ) { contentPadding ->
        TransformingLazyColumn(contentPadding = contentPadding, state = listState) {
            item {
                ListHeader(modifier = Modifier.fillMaxWidth()) { Text("Medição falhou") }
            }
            item {
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────

@WearPreviewDevices
@Composable
fun PreviewBiaInstructions() {
    PulseLabWearTheme { BiaScreenContent(BiaUiState.Instructions, onStart = {}, onRetry = {}) }
}

@WearPreviewDevices
@Composable
fun PreviewBiaMeasuring() {
    PulseLabWearTheme {
        BiaScreenContent(BiaUiState.Measuring(0.6f, contactLost = false), onStart = {}, onRetry = {})
    }
}

@WearPreviewDevices
@Composable
fun PreviewBiaResult() {
    PulseLabWearTheme {
        BiaScreenContent(
            BiaUiState.Done(
                BiaResult(
                    bodyFatRatio = 21.4f, bodyFatMass = 15.9f,
                    skeletalMuscleRatio = 43.1f, skeletalMuscleMass = 32.0f,
                    totalBodyWater = 42.5f, basalMetabolicRate = 1710f,
                    fatFreeMass = 58.4f, fatFreeRatio = 78.6f,
                    impedance = 512f, timestamp = 0L
                )
            ),
            onStart = {}, onRetry = {}
        )
    }
}
