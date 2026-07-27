package br.unioeste.pulselab.wear.presentation.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import br.unioeste.pulselab.wear.model.HealthMetric
import br.unioeste.pulselab.wear.presentation.theme.PulseLabColors

private val ORB_SIZE = 62.dp

/**
 * One metric as a circle: the value inside, the metric's name underneath.
 *
 * The ring carries the only state the wearer needs at a glance — green once the
 * sensor has delivered a real value, dim while it has not. A metric is never
 * hidden for lack of data; an empty orb says "this sensor has nothing yet", which
 * is information too.
 */
@Composable
fun MetricOrb(
    metric: HealthMetric,
    value: String?,
    modifier: Modifier = Modifier
) {
    val hasValue = value != null

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Box(
            modifier = Modifier
                .size(ORB_SIZE)
                .background(PulseLabColors.Card, CircleShape)
                .border(
                    width = if (hasValue) 1.5.dp else 1.dp,
                    color = if (hasValue) PulseLabColors.Green else PulseLabColors.Outline,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = value ?: "—",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (hasValue) PulseLabColors.TextPrimary else PulseLabColors.TextSecondary,
                    textAlign = TextAlign.Center
                )
                if (hasValue && metric.unit.isNotEmpty()) {
                    Text(
                        text = metric.unit,
                        style = MaterialTheme.typography.labelSmall,
                        color = PulseLabColors.TextSecondary
                    )
                }
            }
        }

        Text(
            text = metric.label,
            style = MaterialTheme.typography.labelSmall,
            color = PulseLabColors.TextSecondary,
            textAlign = TextAlign.Center
        )
    }
}
