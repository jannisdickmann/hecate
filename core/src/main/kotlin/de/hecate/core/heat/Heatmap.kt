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
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.hypot

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
 *
 * A cell is [cellM] metres square **on the ground, always** — the raster's
 * dimensions follow from the extent rather than the other way round. See
 * [Tuning.HEATMAP_CELL_M] for why that is the property worth fixing.
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
    /** Edge length [m] of one cell, on the ground. Square by construction:
     *  `spanX == width * cellM` and `spanY == height * cellM`. */
    val cellM: Double,
    /** How many recordings passed through each cell, row-major, row 0 = north. */
    val cells: IntArray,
    val maxCount: Int,
    /** The routes inside the radius, in the order they were given. Everything
     *  drawn on top of this raster must come from here, or the picture would
     *  show a route the raster does not contain. */
    val routeIds: List<Long>,
    val sessionsUsed: Int,
    val sessionsDiscarded: Int,
    val routesDiscarded: Int,
    /** Of [sessionsUsed], how many left no mark at all: inside the radius, and
     *  outside the ground the picture covers. Nothing else on the screen can
     *  say that a recording is missing, because a recording that is not drawn
     *  looks exactly like one that was never made. */
    val sessionsOutsideExtent: Int = 0,
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
            other.cellM == cellM &&
            other.maxCount == maxCount && other.routeIds == routeIds &&
            other.sessionsUsed == sessionsUsed &&
            other.sessionsDiscarded == sessionsDiscarded &&
            other.routesDiscarded == routesDiscarded &&
            other.sessionsOutsideExtent == sessionsOutsideExtent &&
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
 * Five decisions shape the whole thing:
 *
 * - **The routes anchor the extent; the recordings may extend it, but only so
 *   far.** A recording that never became a route — one trip to another city —
 *   must not set the bounding box and squash everything you actually run into a
 *   corner. But a box that stops at the routes cuts real tracks off at an
 *   invisible line, so ground a recording covered is included out to
 *   [Tuning.HEATMAP_EXTENT_REACH] times the routes' own larger side, and
 *   anything past that gets no vote at all.
 * - **The radius filter is about believing coordinates, not about tidiness.** It
 *   is measured from the median start of all routes, so it takes half your
 *   routes moving abroad to move it, and it throws out the fix that landed in
 *   the Atlantic before that fix can define the picture.
 * - **Out-of-extent samples are dropped, never clamped.** Clamping would pile
 *   every distant point onto the border cells and draw a hot frame around a map
 *   that has nothing at its edges.
 * - **A cell is a fixed size on the ground** ([Tuning.HEATMAP_CELL_M]), so the
 *   raster is as many cells across as the extent needs. The alternative — a
 *   fixed number of cells — makes the resolution of the picture depend on where
 *   else the user has been, which is how the same street came out as one blob on
 *   one phone and two lines on another.
 * - **The raster counts passes, not samples** ([drawTrack]). A sample is a
 *   second of somebody's life, so counting samples measures how slowly the
 *   ground was covered: five minutes at a level crossing used to out-shout a
 *   route ridden sixty times, in one cell. A pass is what a person means by "I
 *   have been here often", and it is also what makes the tracks continuous —
 *   the segment between two samples is drawn, so a 12 m cell does not go empty
 *   just because nobody's GPS happened to fire inside it.
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
        cellM: Double = Tuning.HEATMAP_CELL_M,
        maxCells: Int = Tuning.HEATMAP_MAX_CELLS,
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

        val extent = extentOf(keptRoutes, keptSessions, centre, cellM, maxCells)

        val cells = IntArray(extent.width * extent.height)
        var outside = 0
        for (s in keptSessions) if (!drawTrack(s.points, centre, extent, cells)) outside++
        val max = cells.maxOrNull() ?: 0

        return HeatmapGrid(
            originLat = centre.lat,
            originLon = centre.lon,
            minX = extent.minX,
            minY = extent.minY,
            spanX = extent.width * extent.cellM,
            spanY = extent.height * extent.cellM,
            width = extent.width,
            height = extent.height,
            cellM = extent.cellM,
            cells = cells,
            maxCount = max,
            routeIds = keptRoutes.map { it.id },
            sessionsUsed = keptSessions.size,
            sessionsDiscarded = sessions.size - keptSessions.size,
            routesDiscarded = routes.size - keptRoutes.size,
            sessionsOutsideExtent = outside,
        )
    }

    private data class Extent(
        val minX: Double,
        val minY: Double,
        val width: Int,
        val height: Int,
        val cellM: Double,
    ) {
        val maxY: Double get() = minY + height * cellM
    }

    /**
     * One recording's contribution: the line it travelled, not the points it
     * reported, and at most one count per cell it passed through.
     *
     * The segment between consecutive samples is walked in half-cell steps and
     * the cell is only counted when it *changes*, which is what turns "counts
     * samples" into "counts passes". Both halves of that matter and they pull in
     * opposite directions: walking the segment stops a fast rider leaving a
     * dotted line at 12 m resolution, and counting each cell once stops a slow
     * one, or a stopped one, from burning a hole in the picture where they
     * waited.
     *
     * A gap longer than [Tuning.MAX_INTERPOLATION_GAP_M] is a dropout, not a
     * straight line through unknown terrain — the same rule, and the same
     * constant, that resampling a track uses. Its endpoints are still counted;
     * only the line between them is not drawn.
     *
     * @return whether any of it landed inside the picture at all.
     */
    private fun drawTrack(points: List<LatLon>, centre: LatLon, extent: Extent, cells: IntArray): Boolean {
        var previous: DoubleArray? = null
        var lastIndex = -1
        var drew = false

        fun touch(x: Double, y: Double) {
            val col = ((x - extent.minX) / extent.cellM).toInt()
            // Row 0 is the north edge, so the row index counts down from maxY.
            val row = ((extent.maxY - y) / extent.cellM).toInt()
            if (col !in 0 until extent.width || row !in 0 until extent.height) {
                // Outside the picture: dropped, never clamped. The run of cells
                // is broken too, so coming back in counts as arriving.
                lastIndex = -1
                return
            }
            val i = row * extent.width + col
            drew = true
            if (i == lastIndex) return
            cells[i]++
            lastIndex = i
        }

        for (p in points) {
            val here = Geo.toLocal(centre, p)
            val from = previous
            if (from != null) {
                val dx = here[0] - from[0]
                val dy = here[1] - from[1]
                val length = hypot(dx, dy)
                if (length <= Tuning.MAX_INTERPOLATION_GAP_M) {
                    val steps = ceil(length / (extent.cellM / 2.0)).toInt()
                    for (k in 1 until steps) {
                        val f = k.toDouble() / steps
                        touch(from[0] + dx * f, from[1] + dy * f)
                    }
                } else {
                    lastIndex = -1
                }
            }
            touch(here[0], here[1])
            previous = here
        }
        return drew
    }

    /**
     * The ground the picture covers, padded, brought inside the allowed range of
     * shapes, and then rounded outwards to whole cells.
     *
     * Grown, never cropped, and grown symmetrically: the alternative is either
     * losing part of a route off the edge or stretching the geography, and a
     * route drawn with the wrong proportions is a route you no longer recognise.
     *
     * The routes anchor it and the recordings may extend it, out to
     * [Tuning.HEATMAP_EXTENT_REACH] times the routes' larger side. Anchoring it
     * on the routes is what stops one trip to another city from deciding where
     * the picture is (A.14); bounding it *by* the routes was cutting real
     * tracks off at an invisible line, which is a different failure and a worse
     * one, because nothing on the screen says it happened. A recording beyond
     * the reach contributes no part of the extent at all — not even the edge it
     * would have been clamped to, which would stretch the picture to the limit
     * for something not drawn inside it.
     *
     * The cell size doubles until the raster fits in [maxCells]. Doubling rather
     * than scaling to fit exactly, so that the cell stays a round multiple of the
     * one constant and two rasters of the same ground line up instead of being
     * off by a fraction of a cell.
     */
    private fun extentOf(
        routes: List<RouteTrack>,
        sessions: List<SessionTrack>,
        centre: LatLon,
        cellM: Double,
        maxCells: Int,
    ): Extent {
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

        // How far a recording may reach past the routes, in each direction.
        val reach = maxOf(maxX - minX, maxY - minY) * Tuning.HEATMAP_EXTENT_REACH
        val reachMinX = minX - reach
        val reachMaxX = maxX + reach
        val reachMinY = minY - reach
        val reachMaxY = maxY + reach
        for (s in sessions) {
            for (p in s.points) {
                val l = Geo.toLocal(centre, p)
                if (l[0] < reachMinX || l[0] > reachMaxX || l[1] < reachMinY || l[1] > reachMaxY) continue
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

        // One scale for both axes, and only as far as the nearest allowed shape:
        // a picture grown to one fixed ratio is mostly surface, and the empty
        // half of it costs cells that the ground could have had instead.
        val aspect = spanX / spanY
        if (aspect < Tuning.HEATMAP_MIN_ASPECT) spanX = spanY * Tuning.HEATMAP_MIN_ASPECT
        else if (aspect > Tuning.HEATMAP_MAX_ASPECT) spanY = spanX / Tuning.HEATMAP_MAX_ASPECT

        var cell = cellM
        var cols = ceil(spanX / cell).toInt().coerceAtLeast(1)
        var rows = ceil(spanY / cell).toInt().coerceAtLeast(1)
        while (cols.toLong() * rows.toLong() > maxCells) {
            cell *= 2
            cols = ceil(spanX / cell).toInt().coerceAtLeast(1)
            rows = ceil(spanY / cell).toInt().coerceAtLeast(1)
        }

        // Rounding up to whole cells grew the extent a little; give the growth
        // back to both sides so the routes stay centred in the picture.
        return Extent(
            minX = cx - cols * cell / 2,
            minY = cy - rows * cell / 2,
            width = cols,
            height = rows,
            cellM = cell,
        )
    }

    /**
     * A Gaussian blur over the counts, which is what turns a raster into a
     * heatmap.
     *
     * Separable, so it is two one-dimensional passes rather than one
     * two-dimensional one — the same result for a fraction of the work, which is
     * the only reason a blur this wide is affordable over four million cells.
     *
     * Applied on the way out, at drawing time, rather than stored: the counts are
     * what was measured and the blur is a statement about how precisely it was
     * measured. Changing [Tuning.HEATMAP_BLUR_SIGMA_CELLS] then costs a redraw
     * rather than a re-clustering of the entire history.
     *
     * Edges are handled by clamping the kernel to the array rather than by
     * treating the outside as zero, so the border does not fade out into a frame
     * that means nothing.
     */
    fun blur(
        cells: IntArray,
        width: Int,
        height: Int,
        sigmaCells: Double = Tuning.HEATMAP_BLUR_SIGMA_CELLS,
    ): FloatArray {
        val out = FloatArray(cells.size) { cells[it].toFloat() }
        if (sigmaCells <= 0.0 || width <= 0 || height <= 0) return out

        val radius = ceil(3 * sigmaCells).toInt().coerceAtLeast(1)
        val kernel = FloatArray(2 * radius + 1)
        var sum = 0.0
        for (i in -radius..radius) {
            val v = exp(-(i * i) / (2.0 * sigmaCells * sigmaCells))
            kernel[i + radius] = v.toFloat()
            sum += v
        }
        for (i in kernel.indices) kernel[i] = (kernel[i] / sum).toFloat()

        val scratch = FloatArray(cells.size)
        // Horizontal, then vertical.
        for (row in 0 until height) {
            val base = row * width
            for (col in 0 until width) {
                var acc = 0f
                for (k in -radius..radius) {
                    val c = (col + k).coerceIn(0, width - 1)
                    acc += out[base + c] * kernel[k + radius]
                }
                scratch[base + col] = acc
            }
        }
        for (col in 0 until width) {
            for (row in 0 until height) {
                var acc = 0f
                for (k in -radius..radius) {
                    val r = (row + k).coerceIn(0, height - 1)
                    acc += scratch[r * width + col] * kernel[k + radius]
                }
                out[row * width + col] = acc
            }
        }
        return out
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
 * Gzipped JSON, like every other blob this project stores, so the format stays
 * something a person can look at when it goes wrong.
 *
 * The cells are **run-length encoded**, and that is not premature: a fixed
 * physical cell size means the raster is now millions of cells rather than
 * 20,736, and the overwhelming majority of them are zero — a person's tracks
 * cover a few percent of the box that contains them. As pairs of
 * `length, value` the payload is proportional to the ground actually covered
 * instead of to the area it is spread over, which is also what keeps parsing it
 * off the main thread affordable. Gzip alone would compress the zeros just as
 * well; it is the *decoder* that would not survive four million JSON integers.
 */
object HeatmapCodec {

    val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    /**
     * The stored shape.
     *
     * Separate from [HeatmapGrid] so that the in-memory model stays a plain
     * array anything can index, and the storage decision — the run-length
     * encoding — is confined to this file.
     *
     * [cells] and [cellM] are how a raster written before the cell had a fixed
     * physical size still reads: it stored every cell as a JSON integer and knew
     * nothing about [cellM], both of which are recoverable — an old raster is
     * 20,736 cells, and its cell size is its extent divided by its width. The
     * alternative is a routes screen with no picture on it until the next
     * clustering, which is a worse answer to a file this code can plainly
     * understand.
     */
    @Serializable
    private data class Stored(
        val originLat: Double,
        val originLon: Double,
        val minX: Double,
        val minY: Double,
        val spanX: Double,
        val spanY: Double,
        val width: Int,
        val height: Int,
        /** Pairs of run length and value, row-major over the cells. Defaulted
         *  rather than required, so that a payload written before the encoding
         *  existed — which has [cells] instead — is a readable document rather
         *  than a missing field. */
        val runs: IntArray = IntArray(0),
        val maxCount: Int,
        val routeIds: List<Long>,
        val sessionsUsed: Int,
        val sessionsDiscarded: Int,
        val routesDiscarded: Int,
        /** Defaulted, like [cellM]: a raster written before the count existed is
         *  a raster whose author did not know, which is what 0 says here. */
        val sessionsOutsideExtent: Int = 0,
        val cellM: Double = 0.0,
        /** Every cell, as written before the run-length encoding existed. */
        val cells: IntArray? = null,
    )

    fun encode(grid: HeatmapGrid): String = json.encodeToString(
        Stored(
            originLat = grid.originLat,
            originLon = grid.originLon,
            minX = grid.minX,
            minY = grid.minY,
            spanX = grid.spanX,
            spanY = grid.spanY,
            width = grid.width,
            height = grid.height,
            cellM = grid.cellM,
            runs = runLengthEncode(grid.cells),
            maxCount = grid.maxCount,
            routeIds = grid.routeIds,
            sessionsUsed = grid.sessionsUsed,
            sessionsDiscarded = grid.sessionsDiscarded,
            routesDiscarded = grid.routesDiscarded,
            sessionsOutsideExtent = grid.sessionsOutsideExtent,
        ),
    )

    fun decode(text: String): HeatmapGrid {
        val s = json.decodeFromString<Stored>(text)
        val size = s.width * s.height
        return HeatmapGrid(
            originLat = s.originLat,
            originLon = s.originLon,
            minX = s.minX,
            minY = s.minY,
            spanX = s.spanX,
            spanY = s.spanY,
            width = s.width,
            height = s.height,
            cellM = if (s.cellM > 0) s.cellM else s.spanX / s.width,
            cells = when {
                s.runs.isNotEmpty() -> runLengthDecode(s.runs, size)
                s.cells != null -> s.cells.copyOf(size)
                else -> IntArray(size)
            },
            maxCount = s.maxCount,
            routeIds = s.routeIds,
            sessionsUsed = s.sessionsUsed,
            sessionsDiscarded = s.sessionsDiscarded,
            routesDiscarded = s.routesDiscarded,
            sessionsOutsideExtent = s.sessionsOutsideExtent,
        )
    }

    /** Pairs of `length, value`, in order. */
    fun runLengthEncode(cells: IntArray): IntArray {
        if (cells.isEmpty()) return IntArray(0)
        val out = ArrayList<Int>(64)
        var value = cells[0]
        var length = 1
        for (i in 1 until cells.size) {
            if (cells[i] == value) {
                length++
            } else {
                out.add(length); out.add(value)
                value = cells[i]; length = 1
            }
        }
        out.add(length); out.add(value)
        return out.toIntArray()
    }

    /** The inverse, into an array of exactly [size] cells. A payload that claims
     *  more than that is truncated rather than throwing: a raster one cell short
     *  is a picture, a crash on the routes screen is not. */
    fun runLengthDecode(runs: IntArray, size: Int): IntArray {
        val out = IntArray(size)
        var at = 0
        var i = 0
        while (i + 1 < runs.size && at < size) {
            val length = runs[i]
            val value = runs[i + 1]
            val end = (at + length).coerceAtMost(size)
            if (value != 0) java.util.Arrays.fill(out, at, end, value)
            at = end
            i += 2
        }
        return out
    }

    fun writeGzip(grid: HeatmapGrid, out: OutputStream) {
        GZIPOutputStream(out).use { it.write(encode(grid).toByteArray(Charsets.UTF_8)) }
    }

    fun readGzip(input: InputStream): HeatmapGrid =
        GZIPInputStream(input).use { decode(it.readBytes().toString(Charsets.UTF_8)) }
}
