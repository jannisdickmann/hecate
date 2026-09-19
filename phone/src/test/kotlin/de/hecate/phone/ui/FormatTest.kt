package de.hecate.phone.ui

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Only the parts of [Format] that can be wrong without looking wrong. The
 * German number formatting is visible in every screenshot; a name silently
 * pushing a row off the screen is not.
 */
class FormatTest {

    @Test
    fun `a short route name is left exactly as the user wrote it`() {
        assertEquals("Waldrunde", Format.routeName("Waldrunde"))
        // Exactly the limit is still the whole name: the ellipsis has to buy
        // something, and here it would buy nothing.
        val exact = "a".repeat(Format.MAX_ROUTE_NAME_CHARS)
        assertEquals(exact, Format.routeName(exact))
    }

    @Test
    fun `a long route name is cut to twenty characters and an ellipsis`() {
        val cut = Format.routeName("Sonntagsrunde über den Berg und wieder zurück")
        assertEquals("Sonntagsrunde über d…", cut)
        assertEquals(Format.MAX_ROUTE_NAME_CHARS + 1, cut.length)
        assertTrue(cut.endsWith("…"), "the cut must say that it is one")
    }

    @Test
    fun `a cut that lands on a space does not leave it hanging`() {
        // "Runde um den See bei" is twenty characters ending in a word, and the
        // twenty-first is a space: a dangling space before the ellipsis reads as
        // a typo rather than as a shortening.
        assertEquals("Runde um den See bei…", Format.routeName("Runde um den See bei Nacht"))
        assertEquals("Runde um den See…", Format.routeName("Runde um den See    bei Nacht"))
    }
}
