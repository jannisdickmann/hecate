package de.hecate.phone.ui

import androidx.compose.ui.geometry.Offset
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How every map in the app moves under the fingers.
 *
 * Worth a test because the failures are not crashes and not even wrong
 * pictures: a pinch that magnifies the right amount at the wrong place, or a
 * pan that drags empty surface into view, reads as "the map feels off", and
 * nothing that checks a screenshot can see it.
 */
class MapCameraTest {

    private val w = 1080f
    private val h = 2400f
    private val max = 16f

    /** Where a point of the fitted picture lands on screen. */
    private fun MapCamera.at(p: Offset) = toScreen(p)

    /** The point of the fitted picture under a screen position. */
    private fun MapCamera.under(c: Offset) = Offset((c.x - tx) / scale, (c.y - ty) / scale)

    @Test
    fun `the ground under the fingers stays under the fingers`() {
        for (c in listOf(Offset(200f, 300f), Offset(w - 1, h - 1), Offset(0f, 0f), Offset(w / 2, h / 2))) {
            var cam = MapCamera()
            val ground = cam.under(c)
            repeat(6) { cam = cam.pinched(c, Offset.Zero, 1.4f, w, h, max) }
            val now = cam.at(ground)
            assertEquals(c.x, now.x, 0.5f, "x drifted for $c")
            assertEquals(c.y, now.y, 0.5f, "y drifted for $c")
        }
    }

    @Test
    fun `the picture never leaves the screen`() {
        var cam = MapCamera().pinched(Offset(w / 2, h / 2), Offset.Zero, 4f, w, h, max)
        cam = cam.panned(Offset(10_000f, 10_000f), w, h)
        assertEquals(0f, cam.tx); assertEquals(0f, cam.ty)
        cam = cam.panned(Offset(-100_000f, -100_000f), w, h)
        assertEquals(w * (1 - cam.scale), cam.tx, 0.01f)
        assertEquals(h * (1 - cam.scale), cam.ty, 0.01f)
        // At the whole picture there is nowhere to pan to at all.
        assertEquals(MapCamera(), MapCamera().panned(Offset(300f, -200f), w, h))
    }

    @Test
    fun `zoom is bounded at both ends`() {
        val out = MapCamera().pinched(Offset(w / 2, h / 2), Offset.Zero, 0.2f, w, h, max)
        assertEquals(1f, out.scale)
        var cam = MapCamera()
        repeat(40) { cam = cam.pinched(Offset(w / 2, h / 2), Offset.Zero, 1.5f, w, h, max) }
        assertEquals(max, cam.scale)
    }

    @Test
    fun `a double tap zooms in on the tapped spot`() {
        val tap = Offset(800f, 400f)
        val ground = MapCamera().under(tap)
        val cam = MapCamera().zoomedTo(2.5f, tap, w, h, max)
        assertEquals(2.5f, cam.scale)
        assertEquals(tap.x, cam.at(ground).x, 0.5f)
        assertEquals(tap.y, cam.at(ground).y, 0.5f)
    }

    @Test
    fun `visible is what is on screen, in the fitted picture's pixels`() {
        val cam = MapCamera(scale = 2f, tx = -w / 2, ty = -h / 2)
        val v = cam.visible(w, h)
        assertEquals(w / 4, v.left, 0.01f); assertEquals(h / 4, v.top, 0.01f)
        assertEquals(3 * w / 4, v.right, 0.01f); assertEquals(3 * h / 4, v.bottom, 0.01f)
    }

    @Test
    fun `the ground window covers the screen and stays put through a small pan`() {
        val cam = MapCamera(scale = 3f, tx = -w, ty = -h)
        val win = stableWindow(cam, w, h)
        val v = cam.visible(w, h)
        assertTrue(win.left <= v.left && win.top <= v.top && win.right >= v.right && win.bottom >= v.bottom)
        assertEquals(win, stableWindow(cam.panned(Offset(5f, -5f), w, h), w, h))
    }
}
