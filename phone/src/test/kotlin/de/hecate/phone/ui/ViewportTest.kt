package de.hecate.phone.ui

import androidx.compose.ui.geometry.Offset
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pinch arithmetic of the full-screen heatmap.
 *
 * Worth a test because the failure is not a crash and not even a wrong picture:
 * it is a gesture that works, magnifies the right amount, and magnifies the
 * wrong place. That reads as "the zoom feels off", and nothing that checks a
 * screenshot can see it.
 */
class ViewportTest {

    private val windowW = 1080f
    private val windowH = 2400f

    private fun pinch(
        from: Viewport,
        centroid: Offset,
        zoom: Float,
        pan: Offset = Offset.Zero,
        drawnW: Float = windowW,
        drawnH: Float = windowH,
    ) = from.pinched(
        centroid = centroid,
        pan = pan,
        zoom = zoom,
        windowW = windowW,
        windowH = windowH,
        drawnW = drawnW,
        drawnH = drawnH,
    )

    /** Where a point of the picture lands on screen, both measured from the
     *  centre the picture scales about. */
    private fun drawnAt(v: Viewport, picturePoint: Offset) =
        Offset(picturePoint.x * v.scale + v.offset.x, picturePoint.y * v.scale + v.offset.y)

    @Test
    fun `the ground under the fingers stays under the fingers`() {
        // A picture that covers the window — the case where the clamp has
        // somewhere to let it go — pinched 300 px right and 200 px above the
        // middle, six times over.
        val centroid = Offset(300f, -200f)
        val before = Viewport(1f, Offset.Zero)
        val under = Offset(
            (centroid.x - before.offset.x) / before.scale,
            (centroid.y - before.offset.y) / before.scale,
        )

        var v = before
        for (step in 1..6) {
            v = pinch(v, centroid, zoom = 1.3f)
            val now = drawnAt(v, under)
            assertEquals(centroid.x, now.x, 0.5f, "x drifted at step $step")
            assertEquals(centroid.y, now.y, 0.5f, "y drifted at step $step")
        }
        assertTrue(v.scale > 3f, "six pinches of 1.3 should have magnified more than 3x")
    }

    @Test
    fun `the anchoring holds wherever on the screen the fingers are`() {
        // Including the corners, which is where an error in the sign or in the
        // centre the centroid is measured from would show first.
        for (centroid in listOf(
            Offset(-windowW / 2, -windowH / 2),
            Offset(windowW / 2, windowH / 2),
            Offset(-windowW / 2, windowH / 2),
            Offset(0f, 0f),
        )) {
            var v = Viewport(1f, Offset.Zero)
            val under = Offset(centroid.x, centroid.y)
            repeat(4) { v = pinch(v, centroid, zoom = 1.5f) }
            val now = drawnAt(v, under)
            assertEquals(centroid.x, now.x, 0.5f, "x drifted for $centroid")
            assertEquals(centroid.y, now.y, 0.5f, "y drifted for $centroid")
        }
    }

    @Test
    fun `zooming out again returns to where it started`() {
        val centroid = Offset(-420f, 260f)
        var v = Viewport(1f, Offset.Zero)
        repeat(5) { v = pinch(v, centroid, zoom = 1.4f) }
        repeat(5) { v = pinch(v, centroid, zoom = 1f / 1.4f) }
        assertEquals(1f, v.scale, 1e-3f)
        // Back to the untransformed picture, not to some offset corner of it.
        assertEquals(0f, v.offset.x, 0.5f)
        assertEquals(0f, v.offset.y, 0.5f)
    }

    @Test
    fun `the picture cannot be dragged past its own edge`() {
        var v = Viewport(1f, Offset.Zero)
        repeat(4) { v = pinch(v, Offset.Zero, zoom = 1.5f) }
        val zoomed = v.scale
        // A drag far beyond any edge, in both directions in turn.
        v = pinch(v, Offset.Zero, zoom = 1f, pan = Offset(10_000f, 10_000f))
        assertEquals((windowW * zoomed - windowW) / 2f, v.offset.x, 0.5f)
        assertEquals((windowH * zoomed - windowH) / 2f, v.offset.y, 0.5f)
        v = pinch(v, Offset.Zero, zoom = 1f, pan = Offset(-10_000f, -10_000f))
        assertEquals(-(windowW * zoomed - windowW) / 2f, v.offset.x, 0.5f)
        assertEquals(-(windowH * zoomed - windowH) / 2f, v.offset.y, 0.5f)
    }

    @Test
    fun `an axis the picture does not fill stays centred`() {
        // A wide picture on an upright phone: there is no vertical slack to pan
        // into, so it sits in the middle however hard it is dragged, and the
        // anchoring gives that axis up rather than pulling empty surface in.
        var v = Viewport(1f, Offset.Zero)
        repeat(2) { v = pinch(v, Offset(0f, -600f), zoom = 1.3f, drawnH = 600f) }
        v = pinch(v, Offset.Zero, zoom = 1f, pan = Offset(0f, 900f), drawnH = 600f)
        assertEquals(0f, v.offset.y, 1e-3f)
        assertTrue(v.scale > 1f, "the zoom itself must still have happened")
    }

    @Test
    fun `zoom is bounded at both ends`() {
        var v = Viewport(1f, Offset.Zero)
        repeat(30) { v = pinch(v, Offset(100f, 100f), zoom = 2f) }
        assertTrue(v.scale <= 8f, "scale ran away to ${v.scale}")
        repeat(30) { v = pinch(v, Offset(100f, 100f), zoom = 0.5f) }
        assertEquals(1f, v.scale, 1e-3f, "pinching out past the whole picture")
    }
}
