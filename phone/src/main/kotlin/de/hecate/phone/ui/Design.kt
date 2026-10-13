package de.hecate.phone.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.hecate.core.model.ExerciseType
import de.hecate.phone.R

/**
 * The few shapes every screen is built from.
 *
 * One card, one section header, one figure cell — so that the five screens
 * read as one app, and a change to how a card looks is a change in one place.
 * Every colour is still a scheme role (see [HecateTheme]).
 */
object HecateDesign {
    val CardShape = RoundedCornerShape(24.dp)
    val InnerShape = RoundedCornerShape(16.dp)
    val ScreenPadding = 16.dp
    val CardPadding = 18.dp
    val Gap = 12.dp
}

/** Tabular figures: digits all one width, so a column of times lines up and a
 *  number that changes under a moving cursor does not jitter. */
val TextStyle.tabular: TextStyle get() = copy(fontFeatureSettings = "tnum")

/** The card everything sits on. */
@Composable
fun HecateCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    container: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    padding: Dp = HecateDesign.CardPadding,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CardDefaults.cardColors(containerColor = container)
    if (onClick != null) {
        Card(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = HecateDesign.CardShape, colors = colors) {
            Column(Modifier.padding(padding), content = content)
        }
    } else {
        Card(modifier = modifier.fillMaxWidth(), shape = HecateDesign.CardShape, colors = colors) {
            Column(Modifier.padding(padding), content = content)
        }
    }
}

/** A card with a titled header: a figure in a tinted disc, the title, and
 *  whatever belongs at the right-hand end of the header. */
@Composable
fun SectionCard(
    title: String,
    figure: Figure,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    HecateCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FigureBadge(figure)
            Spacer(Modifier.width(12.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            trailing?.invoke()
        }
        Spacer(Modifier.height(14.dp))
        content()
    }
}

@Composable
fun FigureBadge(
    figure: Figure,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    size: Dp = 34.dp,
) {
    Box(
        Modifier.size(size).background(container, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (figure == Figure.WEATHER) WeatherGlyph(de.hecate.core.weather.Sky.PARTLY_CLOUDY, true, size = size * .62f)
        else FigureGlyph(figure, content, size = size * .52f)
    }
}

/** One figure: what it is, the number, its unit. */
data class Stat(val label: String, val value: String, val unit: String = "", val accent: Color? = null)

/** A grid of figures in one card, [columns] wide, hairlines between rows. */
@Composable
fun StatGrid(stats: List<Stat>, modifier: Modifier = Modifier, columns: Int = 3) {
    HecateCard(modifier, padding = 6.dp) {
        stats.chunked(columns).forEachIndexed { row, cells ->
            if (row > 0) {
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f)),
                )
            }
            Row(Modifier.fillMaxWidth()) {
                for (cell in cells) StatCell(cell, Modifier.weight(1f))
                repeat(columns - cells.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
fun StatCell(stat: Stat, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
        Text(
            stat.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                stat.value,
                style = MaterialTheme.typography.headlineSmall.tabular,
                fontWeight = FontWeight.SemiBold,
                color = stat.accent ?: MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            if (stat.unit.isNotEmpty()) {
                Spacer(Modifier.width(3.dp))
                Text(
                    stat.unit,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                    maxLines = 1,
                )
            }
        }
    }
}

/** A small rounded label: a route name, a badge, a delta. */
@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.primaryContainer,
    content: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    outlined: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
) {
    Surface(
        modifier = modifier,
        color = if (outlined) Color.Transparent else container,
        contentColor = if (outlined) MaterialTheme.colorScheme.onSurfaceVariant else content,
        border = if (outlined) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null,
        shape = RoundedCornerShape(50),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(5.dp))
            }
            Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The quiet secondary text under or beside a figure. */
@Composable
fun Quiet(text: String, modifier: Modifier = Modifier, textAlign: TextAlign? = null) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
        textAlign = textAlign,
    )
}

/** A small caps-like label above a group of rows inside a card. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier,
    )
}

@Composable
fun KeyValue(key: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            key,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(16.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium.tabular, textAlign = TextAlign.End)
    }
}

/** The icon for an exercise type (US-8). Over the enum, so a type added
 *  without a drawable is a compile error. */
@Composable
fun ActivityIcon(
    type: ExerciseType,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    val res = when (type) {
        ExerciseType.RUNNING -> R.drawable.ic_run
        ExerciseType.WALKING -> R.drawable.ic_walk
        ExerciseType.HIKING -> R.drawable.ic_hike
        ExerciseType.BIKING -> R.drawable.ic_bike
        ExerciseType.SKIING -> R.drawable.ic_ski
    }
    Icon(painter = painterResource(res), contentDescription = type.label, modifier = modifier, tint = tint)
}

/** An activity's icon on a tinted rounded square: the leading mark of every
 *  list row, so a column of them reads as a column of sports. */
@Composable
fun ActivityBadge(type: ExerciseType, size: Dp = 44.dp) {
    Box(
        Modifier.size(size).clip(HecateDesign.InnerShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        ActivityIcon(type, Modifier.size(size * .52f), MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

/** The centred, friendly text of a screen that has nothing to show yet. */
@Composable
fun Empty(title: String, text: String, figure: Figure = Figure.ROUTE) {
    Box(Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            FigureBadge(
                figure,
                container = MaterialTheme.colorScheme.primaryContainer,
                content = MaterialTheme.colorScheme.onPrimaryContainer,
                size = 72.dp,
            )
            Spacer(Modifier.height(20.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Rise into place on first composition, [index] steps after the first item:
 * the cards of a screen arrive one after another rather than all at once,
 * which is the whole of the motion this app allows itself — enough to show
 * where things came from, never enough to wait for.
 */
@Composable
fun Modifier.appear(index: Int): Modifier {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(40L * index.coerceAtMost(8))
        progress.animateTo(1f, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow))
    }
    return graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 24.dp.toPx()
    }
}

/** A fade for numbers and short labels that change in place. */
val quickFade = tween<Float>(durationMillis = 180)
