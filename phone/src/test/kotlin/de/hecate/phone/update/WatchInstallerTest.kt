package de.hecate.phone.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchInstallerTest {

    @Test
    fun percentIsNullWhileTheSizeIsUnknown() {
        assertNull(WatchInstaller.percent(1_000, -1))
        assertNull(WatchInstaller.percent(1_000, 0))
    }

    @Test
    fun percentRoundsDownAndStaysInRange() {
        assertEquals(0, WatchInstaller.percent(0, 30_000_000))
        assertEquals(33, WatchInstaller.percent(10_000_000, 30_000_000))
        assertEquals(100, WatchInstaller.percent(30_000_000, 30_000_000))
        assertEquals(100, WatchInstaller.percent(31_000_000, 30_000_000))
    }
}
