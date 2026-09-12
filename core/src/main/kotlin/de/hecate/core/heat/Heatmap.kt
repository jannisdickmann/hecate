package de.hecate.core.heat

import de.hecate.core.Tuning
import de.hecate.core.geo.Geo
import de.hecate.core.geo.LatLon
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** One route's geometry, as the heatmap needs it. */
data class RouteTrack(val id: Long, val points: List<LatLon>)

/** One recording's track, as the heatmap needs it. */
data class SessionTrack(val id: String, val points: List<LatLon>)

/**
 * A density raster over every recording, plus the extent it was drawn in.
 *
 * Row-major with **row 0 at the north edge**, which is the order a bitmap wants
 * and the opposite of the order a map's y axis wants — the conversion happens
 * once, here, rather than at every call site.
 *
 * Positions are metres east and north of ([originLat], [originLon]), the same
 * local projection `Geo.toLocal` gives, so anything else drawn in this extent
 * (the route polylines, for one) projects the same way and lines up by
 * construction.
 */
@Serializable
data class HeatmapGrid(
    val originLat: Double,
    val originLon: Double,
    /** West edge, metres east of origin. */
    val minX: Double,
    /** South edge, metres north of origin. */
    val minY: Double,
    val spanX: Double,
    val spanY: Double,
    val width: Int,
    val height: Int,
    /** Sample counts, row-major, row 0 = north. */
    val cells: IntArray,
    val maxCount: Int,
    /** The routes inside the radius, in the order they were given. Everything
     *  drawn on top of this raster must come from here, or the picture would
     *  show a route the raster does not contain. */
    val routeIds: List<Long>,
    val sessionsUsed: Int,
    val sessionsDiscarded: Int,
    val routesDiscarded: Int,
) {
    val isEmpty: Boolean get() = maxCount == 0

    /** North edge, metres north of origin. */
    val maxY: Double get() = minY + spanY

    override fun equals(other: Any?): Boolean =
        other is HeatmapGrid &&
            other.originLat == originLat && other.originLon == originLon &&
            other.minX == minX && other.minY == minY &&
            other.spanX == spanX && other.spanY == spanY &&
            other.width == width && other.height == height &&
            other.maxCount == maxCount && other.routeIds == routeIds &&
            other.sessionsUsed == sessionsUsed &&
            other.sessionsDiscarded == sessionsDiscarded &&
            other.routesDiscarded == routesDiscarded &&
            other.cells.contentEquals(cells)

    override fun hashCode(): Int = 31 * cells.contentHashCode() + width
}

/**
 * Builds the density raster shown above the routes list (US-8).
 *
 * Pure Kotlin and Android-free, per NFR-4: it is geometry, it has edge cases
 * that only show up with real data, and it therefore belongs somewhere a test
 * can reach it.
 *
 * Three decisions shape the whole thing:
 *
 * - **The extent comes from the routes, not the recordings.** A recording that
 *   never became a route — one trip to another city — would otherwise set the
 *   bounding box and squash everything you actually run into a corner. Such a
 *   recording still contributes its heat wherever it crosses the extent; it just
 *   does not get a vote on where the extent is.
 * - **The radius filter is about believing coordinates, not about tidiness.** It
 *   is measured from the median start of all routes, so it takes half your
 *   routes moving abroad to move it, and it throws out the fix that landed in
 *   the Atlantic before that fix can define the picture.
 * - **Out-of-extent samples are dropped, never clamped.** Clamping would pile
 *   every distant point onto the border cells and draw a hot frame around a map
 *   that has nothing at its edges.
 */
object Heatmap {

    /**
     * @param routes every active route's geometry; the extent comes from these.
     * @param sessions every recording's track; the heat comes from these.
     * @return null when there is nothing to draw — no routes, or none that
     *   survive the radius filter, since without routes there is no extent.
     */
    fun build(
        routes: List<RouteTrack>,
        sessions: List<SessionTrack>,
        maxRadiusM: Double = Tuning.HEATMAP_MAX_RADIUS_M,
        width: Int = Tuning.HEATMAP_WIDTH,
        height: Int = Tuning.HEATMAP_HEIGHT,
    ): HeatmapGrid? {
        val centre = medianStart(routes) ?: return null

        val keptRoutes = routes.filter { r ->
            r.points.isNotEmpty() && r.points.all { Geo.haversine(centre, it) <= maxRadiusM }
        }
        if (keptRoutes.isEmpty()) return null

        // A recording is in or out as a whole. A track with one wild fix in it is
        // a track whose every point is suspect, and half a recording drawn is
        // worse than none.
        val keptSessions = sessions.filter { s ->
            s.points.isNotEmpty() && s.points.all { Geo.haversine(centre, it) <= maxRadiusM }
        }

        val extent = extentOf(keptRoutes, centre, width, height)

        val cells = IntArray(width * height)
        var max = 0
        for (s in keptSessions) {
            for (p in s.points) {
                val local = Geo.toLocal(centre, p)
                val col = ((local[0] - extent.minX) / extent.spanX * width).toInt()
                // Row 0 is the north edge, so the row index counts down from maxY.
                val row = ((extent.maxY - local[1]) / extent.spanY * height).toInt()
                if (col !in 0 until width || row !in 0 until height) continue
                val i = row * width + col
                val n = cells[i] + 1
                cells[i] = n
                if (n > max) max = n
            }
        }

        return HeatmapGrid(
            originLat = centre.lat,
            originLon = centre.lon,
            minX = extent.minX,
            minY = extent.minY,
            spanX = extent.spanX,
            spanY = extent.spanY,
            width = width,
            height = height,
            cells = cells,
            maxCount = max,
            routeIds = keptRoutes.map { it.id },
            sessionsUsed = keptSessions.size,
            sessionsDiscarded = sessions.size - keptSessions.size,
            routesDiscarded = routes.size - keptRoutes.size,
        )
    }

    private data class Extent(val minX: Double, val minY: Double, val spanX: Double, val spanY: Double) {
        val maxY: Double get() = minY + spanY
    }

    /**
     * The routes' bounding box, padded, then grown to the raster's aspect ratio.
     *
     * Grown, never cropped, and grown symmetrically: the alternative is either
     * losing part of a route off the edge or stretching the geography, and a
     * route drawn with the wrong proportions is a route you no longer recognise.
     */
    private fun extentOf(routes: List<RouteTrack>, centre: LatLon, width: Int, height: Int): Extent {
        var minX = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        for (r in routes) {
            for (p in r.points) {
                val l = Geo.toLocal(centre, p)
                if (l[0] < minX) minX = l[0]
                if (l[0] > maxX) maxX = l[0]
                if (l[1] < minY) minY = l[1]
                if (l[1] > maxY) maxY = l[1]
            }
        }

        var spanX = (maxX - minX).coerceAtLeast(Tuning.HEATMAP_MIN_SPAN_M)
        var spanY = (maxY - minY).coerceAtLeast(Tuning.HEATMAP_MIN_SPAN_M)
        val cx = (minX + maxX) / 2
        val cy = (minY + maxY) / 2

        val pad = maxOf(spanX, spanY) * Tuning.HEATMAP_PADDING_FRACTION
        spanX += 2 * pad
        spanY += 2 * pad

        // One scale for both axes. Whichever side is short of the target ratio
        // grows around the same centre.
        val target = width.toDouble() / height.toDouble()
        if (spanX / spanY < target) spanX = spanY * target else spanY = spanX / target

        return Extent(minX = cx - spanX / 2, minY = cy - spanY / 2, spanX = spanX, spanY = spanY)
    }

    /**
     * The component-wise median of every route's first point.
     *
     * Component-wise rather than a true geometric median because this only has
     * to be *near* home to be useful, and a coordinate-wise median cannot be
     * dragged anywhere by a minority of outliers — which is the entire job.
     *
     * Note that it takes an actual order statistic and does **not** average the
     * two middle values on an even count, which is what a textbook median does.
     * Averaging would destroy the property this exists for: with four routes of
     * which two start abroad, the mean of the two middle values sits halfway to
     * the outliers, at a place no route has ever been, and the radius filter
     * then rejects every route including the real ones. Taking the lower of the
     * two middles keeps the breakdown point at the 50% a median is supposed to
     * have, and guarantees the centre is somewhere a route actually started.
     */
    fun medianStart(routes: List<RouteTrack>): LatLon? {
        val starts = routes.mapNotNull { it.points.firstOrNull() }
        if (starts.isEmpty()) return null
        return LatLon(
            lat = starts.map { it.lat }.lowerMedian(),
            lon = starts.map { it.lon }.lowerMedian(),
        )
    }

    private fun List<Double>.lowerMedian(): Double = sorted()[(size - 1) / 2]
}

/**
 * Reads and writes the stored raster.
 *
 * Gzipped JSON, like every other blob this project stores: the cells are mostly
 * zero and compress to a fraction of their size, and the format stays something
 * a person can look at when it goes wrong.
 */
object HeatmapCodec {

    val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun encode(grid: HeatmapGrid): String = json.encodeToString(grid)

    fun decode(text: String): HeatmapGrid = json.decodeFromString(text)

    fun writeGzip(grid: HeatmapGrid, out: OutputStream) {
        GZIPOutputStream(out).use { it.write(encode(grid).toByteArray(Charsets.UTF_8)) }
    }

    fun readGzip(input: InputStream): HeatmapGrid =
        GZIPInputStream(input).use { decode(it.readBytes().toString(Charsets.UTF_8)) }
}
