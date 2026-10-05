package de.hecate.phone.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/**
 * Where an interactive map is looking: magnified by [scale] and moved by
 * ([tx], [ty]) canvas pixels.
 *
 * A point drawn at `p` in the fitted picture (scale 1, the whole content on
 * screen) lands on screen at `p·scale + t`. The fitted picture is laid out to
 * fill the canvas exactly — [HecateMap] fits the content into the canvas, with
 * whatever is left over around it showing ground rather than nothing — so the
 * one rule for how far the picture may move is that the canvas stays covered:
 * at scale 1 there is nowhere to go, and zoomed in you can pan as far as the
 * picture's own edge and no further.
 *
 * Pure arithmetic, separate from the gestures that drive it, because the way a
 * map *feels* is decided here and a feeling cannot be screenshot-tested. The
 * old full-screen heatmap's version of this anchored the pinch correctly but
 * scaled about the picture's centre, which made the clamp a three-way case
 * analysis; anchoring at the top left makes every rule one line.
 */
data class MapCamera(val scale: Float = 1f, val tx: Float = 0f, val ty: Float = 0f) {

    /**
     * One step of a pinch: magnified by [zoom] about [centroid] and moved by
     * [pan], all in screen pixels.
     *
     * A zoom has a place. For the ground under the fingers to stay under them
     * when the scale goes from s to s' = k·s, the translation has to become
     * `t' = c − k·(c − t)`: the point at c was drawn at c, and must still be.
     */
    fun pinched(centroid: Offset, pan: Offset, zoom: Float, width: Float, height: Float, maxScale: Float): MapCamera {
        val next = (scale * zoom).coerceIn(1f, maxScale)
        val k = next / scale
        return MapCamera(
            scale = next,
            tx = centroid.x - k * (centroid.x - tx) + pan.x,
            ty = centroid.y - k * (centroid.y - ty) + pan.y,
        ).clamped(width, height)
    }

    /** Moved by [by] screen pixels, as far as the picture's edge allows. */
    fun panned(by: Offset, width: Float, height: Float): MapCamera =
        copy(tx = tx + by.x, ty = ty + by.y).clamped(width, height)

    /** The same pinch rule, to an absolute [target] scale: what a double tap
     *  animates to, keeping the tapped point where it was tapped. */
    fun zoomedTo(target: Float, at: Offset, width: Float, height: Float, maxScale: Float): MapCamera =
        pinched(at, Offset.Zero, target / scale, width, height, maxScale)

    /** Kept so the canvas stays covered by the picture. */
    fun clamped(width: Float, height: Float): MapCamera = copy(
        tx = tx.coerceIn(width * (1 - scale), 0f),
        ty = ty.coerceIn(height * (1 - scale), 0f),
    )

    /**
     * The part of the fitted picture on screen, in the fitted picture's own
     * pixels. This is what makes the basemap a *different* map zoomed in rather
     * than the same one magnified: it asks for the ground in this rectangle, at
     * the detail its size calls for.
     */
    fun visible(width: Float, height: Float): Rect = Rect(
        left = -tx / scale,
        top = -ty / scale,
        right = (width - tx) / scale,
        bottom = (height - ty) / scale,
    )

    /** Where a point of the fitted picture is on screen. */
    fun toScreen(p: Offset): Offset = Offset(p.x * scale + tx, p.y * scale + ty)

    val isIdentity: Boolean get() = scale == 1f && tx == 0f && ty == 0f
}
