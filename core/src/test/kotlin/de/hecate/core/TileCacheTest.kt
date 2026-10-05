package de.hecate.core

import de.hecate.core.map.Level
import de.hecate.core.map.TileCache
import de.hecate.core.map.TileId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TileCacheTest {

    @Test
    fun `several views reading at once do not break it`() {
        // A map card and a full-screen map drawing at the same time, on a
        // dispatcher that is a pool: two threads walking one LinkedHashMap's
        // access order threw ConcurrentModificationException, which was logged
        // as an unreadable map and drew nothing.
        val cache = TileCache(maxTiles = 16)
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        repeat(8) { t ->
            pool.execute {
                start.await()
                try {
                    for (i in 0 until 20_000) {
                        cache.get(Level.GROB, TileId((i * 7 + t) % 40, i % 3)) { _, _ -> null }
                        if (i % 997 == 0) cache.clear()
                    }
                } catch (e: Throwable) {
                    failure.compareAndSet(null, e)
                }
            }
        }
        start.countDown()
        pool.shutdown()
        pool.awaitTermination(60, TimeUnit.SECONDS)
        assertNull(failure.get(), "a concurrent read failed: ${failure.get()}")
        assert(cache.size <= 16)
    }

    @Test
    fun `a miss is loaded once and then served from the cache`() {
        val cache = TileCache(maxTiles = 4)
        var loads = 0
        repeat(3) { cache.get(Level.FEIN, TileId(1, 2)) { _, _ -> loads++; null } }
        assertEquals(1, loads)
    }
}
