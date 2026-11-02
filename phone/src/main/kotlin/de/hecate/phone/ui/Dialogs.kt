package de.hecate.phone.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hecate.core.Tuning
import de.hecate.core.model.ExerciseType

/*
 * The dialogs and small controls more than one screen uses.
 */

/**
 * A header that opens and closes what is under it, closed to begin with.
 *
 * Remembered across recomposition and rotation, not across screens: details
 * opened on one recording are not a reason to open them on the next.
 */
@Composable
internal fun CollapsibleSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { open = !open }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Icon(
            if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = if (open) "Zuklappen" else "Aufklappen",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    AnimatedVisibility(open) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
}

/**
 * Pick the sport a recording should have been filed under.
 *
 * All five are offered rather than only the confusable pairs, because which
 * pairs are confusable is a fact about somebody's morning and not about this
 * app. Choosing applies immediately and closes — there is no second confirm
 * step, since the change is reversible by making it again, which is not true of
 * the delete below it.
 *
 * The note underneath is not a warning. Re-clustering is what the edit is *for*,
 * not a consequence of it, and the only part the user cannot infer is that it
 * travels: the rebuilt bundle goes to the watch, and Health Connect is rewritten
 * to agree.
 */
@Composable
internal fun ActivityTypeDialog(
    current: ExerciseType,
    onPick: (ExerciseType) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Art der Aufzeichnung") },
        text = {
            Column {
                ExerciseType.entries.forEach { type ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(type) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = type == current, onClick = { onPick(type) })
                        Spacer(Modifier.width(4.dp))
                        ActivityIcon(type, Modifier.size(20.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(12.dp))
                        Text(type.label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/**
 * The one shape every confirmation takes.
 *
 * [body] names what is about to happen to what, rather than asking whether the
 * user is sure: by the time this is on screen they have already said so, and the
 * only useful thing left to tell them is what they will not be able to undo.
 */
@Composable
internal fun ConfirmDialog(
    title: String,
    body: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/**
 * The manual route ranking, −2..2.
 *
 * Only matters where two routes share the same ground and the matcher therefore
 * refuses to choose between them (US-4). Raise one above the other and the watch
 * will assume that one — with its name, its remaining distance and its ETA —
 * from the start, instead of showing "Suche…" until the paths part. Leaving
 * everything at 0 keeps the cautious behaviour.
 *
 * One row of five rather than two rows of five out of ten. Only the *order* of
 * the numbers has ever mattered — nothing multiplies by a priority — so the old
 * range asked for a precision it could not use, and asking someone to choose
 * between a 6 and a 7 for a route is asking a question with no answer. Five
 * options fit one row at a comfortable touch target.
 *
 * The numbers stand on their own. Each used to carry a phrase above the row —
 * "0 · keine Meinung", "+1 · eher schon" — and five buttons on a centred scale
 * do not need naming twice: the row already reads left to right as less to
 * more, and the caption under it says what the scale is for. Nor did the
 * caption's second sentence, about a tie leaving the watch on "Suche…", earn
 * its line: it described what happens when the user does nothing, which is what
 * they are already looking at.
 */
@Composable
internal fun PriorityPicker(current: Int, onChange: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        val values = (Tuning.MIN_ROUTE_PRIORITY..Tuning.MAX_ROUTE_PRIORITY).toList()
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            values.forEachIndexed { index, v ->
                SegmentedButton(
                    selected = v == current,
                    onClick = { onChange(v) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = values.size),
                ) { Text(Format.priority(v)) }
            }
        }
    }
}

@Composable
internal fun MonoCard(lines: List<String>) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(10.dp)) {
            for (line in lines) {
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        }
    }
}

@Composable
internal fun MonoRow(a: String, b: String, c: String, d: String, header: Boolean = false) {
    val style = MaterialTheme.typography.bodyMedium.copy(
        fontFamily = FontFamily.Monospace,
        fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
    )
    val color =
        if (header) MaterialTheme.colorScheme.onSurfaceVariant
        else MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(a, style = style, color = color, modifier = Modifier.weight(1.3f))
        Text(b, style = style, color = color, modifier = Modifier.weight(1f))
        Text(c, style = style, color = color, modifier = Modifier.weight(1f))
        Text(d, style = style, color = color, modifier = Modifier.weight(1f))
    }
}

/**
 * Re-read the detail screen once a re-clustering it may have been waiting for
 * finishes: promoting a recording, withdrawing it, or correcting its sport all
 * change what route it belongs to, and the screen that asked should say so
 * without being left and re-entered.
 */
@Composable
internal fun ReloadAfterClustering(vm: HecateViewModel) {
    val clustering by vm.clustering.collectAsStateWithLifecycle()
    var was by remember { mutableStateOf(clustering) }
    LaunchedEffect(clustering) {
        if (was && !clustering) vm.reloadDetail()
        was = clustering
    }
}
