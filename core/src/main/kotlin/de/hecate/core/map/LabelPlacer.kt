package de.hecate.core.map

import de.hecate.core.Tuning
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A rectangle in canvas pixels. */
data class LabelBox(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    fun overlaps(o: LabelBox): Boolean =
        left < o.right && right > o.left && top < o.bottom && bottom > o.top

    fun inside(o: LabelBox): Boolean =
        left >= o.left && right <= o.right && top >= o.top && bottom <= o.bottom

    fun grownBy(m: Double) = LabelBox(left - m, top - m, right + m, bottom + m)
}

/** A place that wants a name on the canvas, already projected. */
data class LabelCandidate(val place: MapPlace, val x: Double, val y: Double)

/** One that got it, with the space it took. */
data class PlacedLabel(val place: MapPlace, val x: Double, val y: Double, val box: LabelBox)

/**
 * Which place names actually fit, decided without a canvas.
 *
 * Greedy in priority order — class rank first, then population, then the name
 * itself so that two equal villages always resolve the same way. Identical
 * input gives identical output, which is not a nicety: a recomposition that
 * reshuffled the labels would make the map flicker while nothing about it
 * changed.
 *
 * **No nudging.** A label moved far enough to fit points at the wrong village,
 * which is worse than no label — so a name that collides is dropped and the
 * ground keeps the one that was already there.
 *
 * **Culling happens before collision**, and that order is the whole bug this
 * function was written around: a label thirty kilometres off screen otherwise
 * wins the contest and suppresses the visible one behind it.
 *
 * The caller measures the text, through [measure], which keeps `TextMeasurer`
 * and everything else Compose on its own side of the module boundary and keeps
 * this testable on a laptop.
 */
object LabelPlacer {

    /**
     * How many labels a canvas of [areaDp2] square dp can carry: about six on a
     * route drawing, about twenty-five full screen.
     */
    fun maxLabelsFor(areaDp2: Double): Int =
        (areaDp2 / Tuning.MAP_LABEL_AREA_PER_LABEL_DP2)
            .roundToInt()
            .coerceIn(Tuning.MAP_LABEL_MIN, Tuning.MAP_LABEL_MAX)

    /**
     * [candidates] in the order they will be drawn.
     *
     * [measure] returns the width and height of the drawn name in pixels;
     * [visible] is the rectangle a label has to fit entirely inside.
     */
    fun place(
        candidates: List<LabelCandidate>,
        measure: (MapPlace) -> DoubleArray,
        visible: LabelBox,
        maxLabels: Int,
        marginPx: Double,
        gapPx: Double = DOT_GAP_PX,
    ): List<PlacedLabel> {
        if (maxLabels <= 0) return emptyList()
        val ordered = candidates.sortedWith(
            compareBy<LabelCandidate> { it.place.cls.rank }
                .thenByDescending { it.place.population }
                .thenBy { it.place.name }
                .thenBy { it.place.at.lat }
                .thenBy { it.place.at.lon },
        )
        val placed = ArrayList<PlacedLabel>(min(maxLabels, ordered.size))
        val taken = ArrayList<LabelBox>(placed.size)
        for (c in ordered) {
            if (placed.size >= maxLabels) break
            val size = measure(c.place)
            val box = boxFor(c, size[0], size[1], gapPx)
            if (!box.inside(visible)) continue
            val claimed = box.grownBy(marginPx)
            if (taken.any { it.overlaps(claimed) }) continue
            placed.add(PlacedLabel(c.place, c.x, c.y, box))
            taken.add(claimed)
        }
        return placed
    }

    /**
     * Where the name sits relative to its dot: centred on it horizontally, and
     * below it, so the dot stays the thing that points at the ground and the
     * text never covers it.
     *
     * [DOT_GAP_PX] is the gap between the two, and the drawing has to use the
     * same number — which is why it is here rather than in the composable, and
     * why it is a parameter as well as a default: inside a pinched layer the
     * gap is measured in the layer's own shrunken pixels.
     */
    const val DOT_GAP_PX: Double = 7.0

    fun boxFor(
        c: LabelCandidate,
        widthPx: Double,
        heightPx: Double,
        gapPx: Double = DOT_GAP_PX,
    ) = LabelBox(
        left = c.x - widthPx / 2.0,
        top = c.y + gapPx,
        right = c.x + widthPx / 2.0,
        bottom = c.y + gapPx + heightPx,
    )

    /** The box a canvas offers, given its size. */
    fun visibleBox(widthPx: Double, heightPx: Double) = LabelBox(0.0, 0.0, max(0.0, widthPx), max(0.0, heightPx))
}
