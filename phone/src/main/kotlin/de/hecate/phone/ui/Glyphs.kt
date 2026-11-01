package de.hecate.phone.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.hecate.core.weather.Sky
import kotlin.math.cos
import kotlin.math.sin

/**
 * Drawn glyphs: the weather symbols and the handful of figure icons the
 * analysis cards use.
 *
 * Drawn rather than shipped as vectors for the reason `material-icons-extended`
 * was declined (A.11): release builds are not minified, and a thousand icons
 * for twenty is a poor trade. Drawing them also lets the weather symbols carry
 * fixed colours — a sun is yellow whatever the wallpaper is, for the same
 * reason the intensity bands are fixed: the colour is part of what it says.
 */
object WeatherColors {
    val Sun = Color(0xFFFFB300)
    val SunCore = Color(0xFFFFCA28)
    val Rain = Color(0xFF1E88E5)
    val Snow = Color(0xFF81D4FA)
    val Bolt = Color(0xFFFDD835)

    /** The temperature line: warm, and distinct from every intensity band. */
    val Temperature = Color(0xFFF4511E)

    fun cloud(dark: Boolean) = if (dark) Color(0xFFCFD8DC) else Color(0xFFB0BEC5)
    fun cloudShade(dark: Boolean) = if (dark) Color(0xFF90A4AE) else Color(0xFF78909C)
    fun moon(dark: Boolean) = if (dark) Color(0xFFFFF59D) else Color(0xFFFBC02D)
    fun fog(dark: Boolean) = if (dark) Color(0xFFB0BEC5) else Color(0xFF90A4AE)
}

/** A weather symbol for [sky], by night when [day] is false. */
@Composable
fun WeatherGlyph(sky: Sky, day: Boolean, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    val dark = isSystemInDarkTheme()
    Canvas(modifier.size(size)) { drawWeather(sky, day, dark) }
}

/** Draws [sky] into the whole of this scope, which should be square. */
fun DrawScope.drawWeather(sky: Sky, day: Boolean, dark: Boolean) {
    val s = size.minDimension
    val cloud = WeatherColors.cloud(dark)
    when (sky) {
        Sky.CLEAR -> if (day) sun(Offset(.5f * s, .5f * s), .22f * s) else moon(Offset(.5f * s, .5f * s), .3f * s, dark)
        Sky.PARTLY_CLOUDY -> {
            if (day) sun(Offset(.38f * s, .36f * s), .16f * s) else moon(Offset(.4f * s, .36f * s), .22f * s, dark)
            cloud(Offset(.12f * s, .38f * s), .78f * s, cloud, WeatherColors.cloudShade(dark))
        }
        Sky.CLOUDY -> {
            cloud(Offset(.24f * s, .14f * s), .66f * s, WeatherColors.cloudShade(dark), null)
            cloud(Offset(.06f * s, .3f * s), .82f * s, cloud, WeatherColors.cloudShade(dark))
        }
        Sky.FOG -> {
            val c = WeatherColors.fog(dark)
            val w = .09f * s
            listOf(.3f to .8f, .18f to .7f, .3f to .86f, .22f to .62f).forEachIndexed { i, (a, b) ->
                val y = (.26f + i * .17f) * s
                drawLine(c, Offset(a * s, y), Offset(b * s, y), strokeWidth = w, cap = StrokeCap.Round)
            }
        }
        Sky.DRIZZLE -> {
            cloud(Offset(.08f * s, .08f * s), .84f * s, cloud, WeatherColors.cloudShade(dark))
            for (x in listOf(.32f, .5f, .68f)) {
                drawCircle(WeatherColors.Rain, radius = .045f * s, center = Offset(x * s, .8f * s))
            }
        }
        Sky.RAIN, Sky.SHOWERS -> {
            if (sky == Sky.SHOWERS) {
                if (day) sun(Offset(.66f * s, .26f * s), .13f * s) else moon(Offset(.66f * s, .26f * s), .18f * s, dark)
            }
            cloud(Offset(.06f * s, .06f * s), .84f * s, cloud, WeatherColors.cloudShade(dark))
            val xs = if (sky == Sky.RAIN) listOf(.3f, .5f, .7f) else listOf(.38f, .6f)
            for (x in xs) drop(Offset(x * s, .74f * s), .2f * s)
        }
        Sky.SLEET -> {
            cloud(Offset(.06f * s, .06f * s), .84f * s, cloud, WeatherColors.cloudShade(dark))
            drop(Offset(.36f * s, .74f * s), .2f * s)
            flake(Offset(.64f * s, .8f * s), .09f * s)
        }
        Sky.SNOW -> {
            cloud(Offset(.06f * s, .04f * s), .84f * s, cloud, WeatherColors.cloudShade(dark))
            for ((x, y) in listOf(.3f to .78f, .52f to .86f, .72f to .76f)) flake(Offset(x * s, y * s), .08f * s)
        }
        Sky.THUNDER -> {
            cloud(Offset(.06f * s, .04f * s), .84f * s, WeatherColors.cloudShade(dark), null)
            val bolt = Path().apply {
                moveTo(.54f * s, .5f * s)
                lineTo(.38f * s, .76f * s)
                lineTo(.5f * s, .76f * s)
                lineTo(.42f * s, .98f * s)
                lineTo(.66f * s, .68f * s)
                lineTo(.53f * s, .68f * s)
                lineTo(.62f * s, .5f * s)
                close()
            }
            drawPath(bolt, WeatherColors.Bolt)
        }
    }
}

private fun DrawScope.sun(c: Offset, r: Float) {
    for (i in 0 until 8) {
        val a = (i * 45.0) * Math.PI / 180
        val from = Offset(c.x + cos(a).toFloat() * r * 1.4f, c.y + sin(a).toFloat() * r * 1.4f)
        val to = Offset(c.x + cos(a).toFloat() * r * 1.85f, c.y + sin(a).toFloat() * r * 1.85f)
        drawLine(WeatherColors.Sun, from, to, strokeWidth = r * .32f, cap = StrokeCap.Round)
    }
    drawCircle(WeatherColors.Sun, radius = r, center = c)
    drawCircle(WeatherColors.SunCore, radius = r * .7f, center = c)
}

private fun DrawScope.moon(c: Offset, r: Float, dark: Boolean) {
    val disc = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, r)) }
    val bite = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(c.x + r * .55f, c.y - r * .45f), r * .9f)) }
    val crescent = Path().apply { op(disc, bite, PathOperation.Difference) }
    drawPath(crescent, WeatherColors.moon(dark))
}

/** A cloud [w] wide with its top-left at [at]; [shade] underlines its base. */
private fun DrawScope.cloud(at: Offset, w: Float, color: Color, shade: Color?) {
    val h = w * .62f
    val p = Path().apply {
        addOval(androidx.compose.ui.geometry.Rect(Offset(at.x + w * .3f, at.y + h * .42f), w * .19f))
        addOval(androidx.compose.ui.geometry.Rect(Offset(at.x + w * .52f, at.y + h * .32f), w * .24f))
        addOval(androidx.compose.ui.geometry.Rect(Offset(at.x + w * .74f, at.y + h * .52f), w * .17f))
        addRoundRect(
            RoundRect(
                left = at.x + w * .1f, top = at.y + h * .5f, right = at.x + w * .92f, bottom = at.y + h * .98f,
                cornerRadius = CornerRadius(h * .24f),
            ),
        )
    }
    if (shade != null) {
        translate(0f, h * .06f) { drawPath(p, shade) }
    }
    drawPath(p, color)
}

private fun DrawScope.drop(top: Offset, len: Float) {
    drawLine(
        WeatherColors.Rain,
        top,
        Offset(top.x - len * .3f, top.y + len),
        strokeWidth = len * .3f,
        cap = StrokeCap.Round,
    )
}

private fun DrawScope.flake(c: Offset, r: Float) {
    for (i in 0 until 3) {
        rotate(i * 60f, pivot = c) {
            drawLine(WeatherColors.Snow, Offset(c.x - r, c.y), Offset(c.x + r, c.y), strokeWidth = r * .45f, cap = StrokeCap.Round)
        }
    }
}

/** The figure icons of the analysis cards, line-drawn in the content colour. */
enum class Figure { TIMER, DISTANCE, SPEED, MOUNTAIN, HEART, SPLITS, ZONES, CHART, ROUTE, WEATHER, CALENDAR, TROPHY, UPDATE }

@Composable
fun FigureGlyph(figure: Figure, color: Color, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    Canvas(modifier.size(size)) { drawFigure(figure, color) }
}

fun DrawScope.drawFigure(figure: Figure, color: Color) {
    val s = size.minDimension
    val stroke = Stroke(width = s * .095f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun p(x: Float, y: Float) = Offset(x * s, y * s)
    when (figure) {
        Figure.TIMER -> {
            drawCircle(color, radius = .36f * s, center = p(.5f, .56f), style = stroke)
            drawLine(color, p(.5f, .56f), p(.5f, .36f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawLine(color, p(.4f, .1f), p(.6f, .1f), strokeWidth = stroke.width, cap = StrokeCap.Round)
        }
        Figure.DISTANCE, Figure.ROUTE -> {
            val path = Path().apply {
                moveTo(.18f * s, .82f * s)
                cubicTo(.18f * s, .5f * s, .82f * s, .62f * s, .78f * s, .3f * s)
            }
            drawPath(path, color, style = stroke)
            drawCircle(color, radius = .1f * s, center = p(.18f, .82f))
            drawCircle(color, radius = .12f * s, center = p(.78f, .22f), style = stroke)
        }
        Figure.SPEED -> {
            drawArc(color, 150f, 240f, false, topLeft = p(.12f, .16f), size = Size(.76f * s, .76f * s), style = stroke)
            drawLine(color, p(.5f, .54f), p(.7f, .34f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawCircle(color, radius = .07f * s, center = p(.5f, .54f))
        }
        Figure.MOUNTAIN -> {
            val path = Path().apply {
                moveTo(.06f * s, .84f * s)
                lineTo(.38f * s, .3f * s)
                lineTo(.56f * s, .58f * s)
                lineTo(.68f * s, .42f * s)
                lineTo(.94f * s, .84f * s)
                close()
            }
            drawPath(path, color, style = stroke)
        }
        Figure.HEART -> {
            val path = Path().apply {
                moveTo(.5f * s, .86f * s)
                cubicTo(.1f * s, .58f * s, .04f * s, .3f * s, .28f * s, .2f * s)
                cubicTo(.4f * s, .15f * s, .48f * s, .24f * s, .5f * s, .32f * s)
                cubicTo(.52f * s, .24f * s, .6f * s, .15f * s, .72f * s, .2f * s)
                cubicTo(.96f * s, .3f * s, .9f * s, .58f * s, .5f * s, .86f * s)
                close()
            }
            drawPath(path, color, style = stroke)
        }
        Figure.SPLITS -> {
            listOf(.55f, .35f, .7f, .45f).forEachIndexed { i, h ->
                val x = (.18f + i * .21f) * s
                drawLine(color, Offset(x, .86f * s), Offset(x, (.86f - h) * s), strokeWidth = stroke.width * 1.3f, cap = StrokeCap.Round)
            }
        }
        Figure.ZONES -> {
            listOf(.2f, .4f, .6f, .8f).forEachIndexed { i, y ->
                val w = listOf(.4f, .7f, .55f, .3f)[i]
                drawLine(color, p(.14f, y), Offset((.14f + w) * s, y * s), strokeWidth = stroke.width * 1.2f, cap = StrokeCap.Round)
            }
        }
        Figure.CHART -> {
            val path = Path().apply {
                moveTo(.1f * s, .74f * s)
                lineTo(.34f * s, .46f * s)
                lineTo(.54f * s, .6f * s)
                lineTo(.9f * s, .22f * s)
            }
            drawPath(path, color, style = stroke)
        }
        Figure.WEATHER -> {
            scale(1f) { drawWeather(Sky.PARTLY_CLOUDY, day = true, dark = false) }
        }
        Figure.CALENDAR -> {
            drawRoundRect(color, topLeft = p(.12f, .2f), size = Size(.76f * s, .68f * s), cornerRadius = CornerRadius(.12f * s), style = stroke)
            drawLine(color, p(.12f, .42f), p(.88f, .42f), strokeWidth = stroke.width)
            drawLine(color, p(.34f, .1f), p(.34f, .26f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawLine(color, p(.66f, .1f), p(.66f, .26f), strokeWidth = stroke.width, cap = StrokeCap.Round)
        }
        Figure.UPDATE -> {
            // An arrow down into a tray: a download.
            drawLine(color, p(.5f, .12f), p(.5f, .6f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            val head = Path().apply {
                moveTo(.3f * s, .42f * s)
                lineTo(.5f * s, .62f * s)
                lineTo(.7f * s, .42f * s)
            }
            drawPath(head, color, style = stroke)
            val tray = Path().apply {
                moveTo(.14f * s, .64f * s)
                lineTo(.14f * s, .86f * s)
                lineTo(.86f * s, .86f * s)
                lineTo(.86f * s, .64f * s)
            }
            drawPath(tray, color, style = stroke)
        }
        Figure.TROPHY -> {
            val cup = Path().apply {
                moveTo(.28f * s, .16f * s)
                lineTo(.72f * s, .16f * s)
                lineTo(.68f * s, .44f * s)
                cubicTo(.64f * s, .6f * s, .36f * s, .6f * s, .32f * s, .44f * s)
                close()
            }
            drawPath(cup, color, style = stroke)
            drawLine(color, p(.5f, .6f), p(.5f, .76f), strokeWidth = stroke.width)
            drawLine(color, p(.34f, .84f), p(.66f, .84f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawArc(color, 90f, 180f, false, topLeft = p(.12f, .2f), size = Size(.2f * s, .22f * s), style = stroke)
            drawArc(color, 270f, 180f, false, topLeft = p(.68f, .2f), size = Size(.2f * s, .22f * s), style = stroke)
        }
    }
}
