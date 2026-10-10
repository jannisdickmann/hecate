package de.hecate.phone.ui

import android.graphics.Bitmap
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import de.hecate.core.heat.Heatmap
import de.hecate.core.heat.HeatmapGrid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ln
import kotlin.math.roundToInt

/*
 * The heatmap's colours: a density grid in, a bitmap out (US-8, A.21). Drawn
 * by [HecateMap] like any other map content — see [rememberHeatMap].
 *
 * The grid arrives precomputed from `ClusterWorker` (Appendix A.14). Three
 * things make the difference between a heatmap and a spreadsheet with the
 * numbers hidden, and only the last of them is here: cells a fixed 15 m on the
 * ground however much ground there is, tracks drawn as lines rather than as the
 * points a receiver happened to report — both in `Heatmap.build` — and a
 * Gaussian blur before the colour ramp, which is what turns counts into density.
 */

/**
 * The coloured raster, built off the main thread and kept until the data or the
 * theme changes.
 *
 * Hoisted out of the map so that the card on the routes list and the
 * full-screen view share one bitmap rather than building the same twelve
 * megabytes twice, and suspended rather than computed in composition because at
 * a fixed cell size the raster is now millions of cells: blurring and colouring
 * them is tens of milliseconds of real work, and composition is the frame the
 * user is waiting for.
 *
 * Null until it is ready, which is a frame or two showing the empty extent —
 * that is the honest thing to show while there is nothing to draw yet, and it is
 * the same surface colour the finished picture sits on, so nothing flashes.
 */
@Composable
fun rememberHeatmapRaster(
    grid: HeatmapGrid,
    dark: Boolean = isSystemInDarkTheme(),
): ImageBitmap? {
    val state = produceState<ImageBitmap?>(initialValue = null, grid, dark) {
        value = withContext(Dispatchers.Default) { rasterOf(grid, dark) }
    }
    return state.value
}

/**
 * Turns counts into pixels.
 *
 * Density is a magnitude, so the ramp is **one hue, stepped light to dark** —
 * never a rainbow, which encodes order nobody can read back. Blue, so that the
 * one thing drawn on top of it can be warm and stay separable from the scale at
 * every step (see [rememberHeatMap]).
 *
 * Dark mode is its own ramp rather than an inversion of this one: on a dark
 * ground, "near zero" has to recede *towards* the surface, so the ramp runs
 * dark-to-light, and the steps are chosen against the dark surface rather than
 * flipped from the light ones.
 *
 * The scale is logarithmic. A route run two hundred times and a lane crossed
 * twice differ by two orders of magnitude, and on a linear scale everything but
 * the commute would be indistinguishable from empty.
 *
 * The blur comes first, and the normalisation is against the *blurred* maximum
 * rather than the raw one — a blur spreads a peak out and lowers it, and
 * dividing by the height of a peak that is no longer there would leave the whole
 * picture a shade too faint.
 */
private fun rasterOf(grid: HeatmapGrid, dark: Boolean): ImageBitmap? {
    if (grid.isEmpty) return null
    val ramp = if (dark) DARK_RAMP else LIGHT_RAMP
    val density = Heatmap.blur(grid.cells, grid.width, grid.height)
    val peak = density.max()
    if (peak <= 0f) return null
    val denominator = ln(1.0 + peak)

    val pixels = IntArray(density.size)
    for (i in density.indices) {
        val value = density[i]
        if (value < FADE_FLOOR) continue // stays fully transparent
        val t = (ln(1.0 + value) / denominator).coerceIn(0.0, 1.0)
        val argb = sample(ramp, t)
        // The outermost ring of the blur fades out rather than stopping at the
        // ramp's lowest step, which would draw a hard edge around every track —
        // the exact artefact the blur is there to remove.
        val fade = (t / LOW_FADE).coerceAtMost(1.0)
        pixels[i] = scaleAlpha(argb, fade)
    }
    return Bitmap.createBitmap(pixels, grid.width, grid.height, Bitmap.Config.ARGB_8888)
        .asImageBitmap()
}

/** Below this, a cell is the tail of somebody else's blur and is not drawn. */
private const val FADE_FLOOR = 0.02f

/**
 * The share of the scale over which the lowest step fades in from nothing.
 *
 * Narrow on purpose. It exists to stop the outermost ring of a blur drawing a
 * hard edge, and nothing else — at 0.18 it was also fading out the lanes
 * themselves: a cell crossed once lands at about t = 0.10 on a history whose
 * busiest cell has been crossed forty times, so a lane came out at little
 * more than half of an already faint step and was, in practice, invisible.
 */
private const val LOW_FADE = 0.06

/** Linear interpolation between the ramp's steps, premultiplied-safe ARGB. */
private fun sample(ramp: IntArray, t: Double): Int {
    val pos = t * (ramp.size - 1)
    val lo = pos.toInt().coerceIn(0, ramp.size - 1)
    val hi = (lo + 1).coerceAtMost(ramp.size - 1)
    val f = pos - lo
    return lerpArgb(ramp[lo], ramp[hi], f)
}

private fun scaleAlpha(argb: Int, factor: Double): Int {
    val alpha = (((argb ushr 24) and 0xFF) * factor).roundToInt().coerceIn(0, 255)
    return (alpha shl 24) or (argb and 0x00FFFFFF)
}

private fun lerpArgb(a: Int, b: Int, f: Double): Int {
    fun ch(shift: Int): Int {
        val x = (a shr shift) and 0xFF
        val y = (b shr shift) and 0xFF
        return (x + (y - x) * f).roundToInt().coerceIn(0, 255)
    }
    return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

private fun argb(alpha: Int, hex: Long): Int =
    Color(hex.toInt() or 0xFF000000.toInt()).copy(alpha = alpha / 255f).toArgb()

/**
 * Light surface: the blue ramp's steps 200 → 800, lightest first.
 *
 * The bottom of the ramp is where a heatmap is won or lost, because almost
 * everything on it is down there: the lane you crossed twice is most of the
 * picture by area, and the commute is a handful of cells. The first version put
 * the lowest step at 27 % alpha of a very pale blue, which on a near-white
 * surface is a colour you have to be told is there. It is now a step darker and
 * half again as opaque — still the quietest thing on the picture, and now
 * quietly visible, which is the difference between a scale and a decoration.
 *
 * The top of the ramp went darker by the same amount, so the range between a
 * lane and a commute is no smaller for the bottom having come up.
 */
private val LIGHT_RAMP = intArrayOf(
    argb(115, 0x9ec5f4),
    argb(170, 0x6da7ec),
    argb(215, 0x3987e5),
    argb(240, 0x1f5fb0),
    argb(255, 0x144684),
    argb(255, 0x0a2a55),
)

/**
 * Dark surface: the same hue, run the other way — dimmest at the bottom so low
 * density sinks into the ground, bright at the top.
 *
 * Lifted at the bottom for the reason the light ramp is, and further: a dark
 * blue at low alpha over a dark surface is nearly the surface, so the lowest
 * step is a mid blue rather than the darkest one on the scale.
 */
private val DARK_RAMP = intArrayOf(
    argb(120, 0x2f6fc4),
    argb(175, 0x3987e5),
    argb(215, 0x6da7ec),
    argb(240, 0x9ec5f4),
    argb(255, 0xcde2fb),
    argb(255, 0xeef4ff),
)
