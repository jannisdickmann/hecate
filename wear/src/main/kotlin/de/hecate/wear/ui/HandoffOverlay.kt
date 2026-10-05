package de.hecate.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import de.hecate.wear.AppGraph
import de.hecate.wear.recorder.Handoff
import kotlinx.coroutines.delay

/**
 * What happens to a recording after Beenden, until the phone has it.
 *
 * Before this the watch went straight back to the start screen, where the only
 * trace of the recording was "1 offen" in small type — and since the phone's
 * acknowledgement is what deletes the file from the watch, "did it arrive?" was
 * a question the user had to answer by opening the phone.
 *
 * So: a spinner while the session is on its way, with a button to stop
 * watching; the transfer does not depend on anybody watching it, and the Data
 * Layer keeps offering it until the phone is back in range. Then, when the
 * phone confirms the session is in its database — the acknowledgement, not the
 * Data Layer's delivery — a tick for [CONFIRMED_SHOWN_MS], and the start screen.
 */
@Composable
internal fun HandoffOverlay(handoff: Handoff?) {
    if (handoff == null || handoff.dismissed) return
    if (handoff.confirmed) {
        LaunchedEffect(handoff.sessionId) {
            delay(CONFIRMED_SHOWN_MS)
            AppGraph.recorder.update { s -> s.copy(handoff = s.handoff?.takeIf { it.sessionId != handoff.sessionId }) }
        }
    }
    // How long it has been waiting, to say so once it is long enough to wonder.
    val waitedMs by produceState(0L, handoff.startedAtMs) {
        while (true) {
            value = System.currentTimeMillis() - handoff.startedAtMs
            delay(1_000)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Swallows taps meant for the start screen underneath.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (handoff.confirmed) {
                Text("✓", fontSize = 40.sp, color = RouteTone.Confirmed)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Auf dem Handy gespeichert",
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Center,
                )
            } else {
                CircularProgressIndicator(Modifier.size(40.dp))
                Spacer(Modifier.height(10.dp))
                Text(
                    "Wird ans Handy übertragen…",
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Center,
                )
                if (waitedMs > SLOW_AFTER_MS) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Handy nicht erreichbar? Geht weiter, sobald es in der Nähe ist.",
                        style = MaterialTheme.typography.bodyExtraSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        AppGraph.recorder.update { s ->
                            s.copy(handoff = s.handoff?.takeIf { it.sessionId == handoff.sessionId }?.copy(dismissed = true) ?: s.handoff)
                        }
                    },
                    label = { Text("Im Hintergrund") },
                )
            }
        }
    }
}

/** How long the tick stays up once the phone has confirmed. */
private const val CONFIRMED_SHOWN_MS = 3_000L

/** When the spinner starts saying why it might be taking a while. */
private const val SLOW_AFTER_MS = 20_000L
