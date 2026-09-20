package de.hecate.core.map

import de.hecate.core.geo.Geo
import de.hecate.core.geo.LatLon

/**
 * The map as a picture, rendered from the decoded store.
 *
 * This is how the basemap gets eyeballed without a device, and it is
 * deliberately rendered *from the store* rather than from the parsed response —
 * so the preview doubles as a decoder test, and an SVG that looks right is
 * evidence that the tiles are right.
 *
 * **The user's own tracks go on top, in red**, because "does the map cover where
 * I actually went, and is it aligned?" is the question a picture of roads alone
 * cannot answer. That question has exactly two wrong answers — a map of the
 * wrong ground, and a map half a kilometre off it — and both are obvious here
 * and invisible in any test that compares numbers.
 */
object BasemapSvg {

    /** Stroke width [px] and colour per class, dim to loud. */
    private val STYLE: Map<MapClass, Pair<Double, String>> = mapOf(
        MapClass.URBAN to (0.0 to "#e8e4dd"),
        MapClass.WATER_AREA to (0.0 to "#b9d4e6"),
        MapClass.WATERWAY to (1.0 to "#9cc3da"),
        MapClass.PATH to (0.4 to "#c9c4bc"),
        MapClass.SERVICE to (0.5 to "#c0bab1"),
        MapClass.MINOR to (0.7 to "#a8a29a"),
        MapClass.RAIL to (0.8 to "#9a9a9a"),
        MapClass.PRIMARY to (1.2 to "#8a8479"),
        MapClass.MOTORWAY to (1.6 to "#6f6a60"),
    )

    /**
     * One region as an SVG document.
     *
     * [widthPx] sets the scale; the height follows from the shape of the ground,
     * so a region is never drawn out of proportion.
     */
    fun render(
        store: TileStore,
        level: Level,
        bounds: LatLonBounds,
        tracks: List<List<LatLon>> = emptyList(),
        widthPx: Double = 1600.0,
        title: String = "",
    ): String {
        val origin = LatLon(bounds.minLat, bounds.minLon)
        val ne = Geo.toLocal(origin, LatLon(bounds.maxLat, bounds.maxLon))
        val spanX = ne[0].coerceAtLeast(1.0)
        val spanY = ne[1].coerceAtLeast(1.0)
        val scale = widthPx / spanX
        val heightPx = spanY * scale
        val view = MapView(origin, scale, 0.0, heightPx)

        val cache = TileCache(PREVIEW_TILE_CACHE)
        // Everything the level holds, so the preview shows what was stored
        // rather than what one zoom level would have drawn.
        val scene = BasemapQuery.query(
            store, bounds,
            dpPerM = if (level == Level.FEIN) 1.0 else 0.0,
            cache = cache,
        )

        val sb = StringBuilder(1 shl 16)
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append(
            "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"%.0f\" height=\"%.0f\" viewBox=\"0 0 %.0f %.0f\">\n"
                .format(java.util.Locale.ROOT, widthPx, heightPx, widthPx, heightPx),
        )
        sb.append("<rect width=\"100%\" height=\"100%\" fill=\"#f7f5f1\"/>\n")

        for (patch in scene.urbanPatches) appendFeature(sb, view, patch, fill = true)
        for (f in scene.features.sortedBy { it.cls.drawOrder }) {
            appendFeature(sb, view, f, fill = f.cls.closed)
        }

        for (track in tracks) {
            if (track.size < 2) continue
            sb.append("<polyline fill=\"none\" stroke=\"#d1332e\" stroke-width=\"1.1\" stroke-opacity=\"0.85\" points=\"")
            for (p in track) {
                val c = view.canvas(p)
                sb.append("%.1f,%.1f ".format(java.util.Locale.ROOT, c[0], c[1]))
            }
            sb.append("\"/>\n")
        }

        for (place in scene.places.sortedBy { it.cls.rank }) {
            val c = view.canvas(place.at)
            if (c[0] < 0 || c[1] < 0 || c[0] > widthPx || c[1] > heightPx) continue
            val size = 13.0 - place.cls.rank
            sb.append("<circle cx=\"%.1f\" cy=\"%.1f\" r=\"2\" fill=\"#5a544b\"/>\n".format(java.util.Locale.ROOT, c[0], c[1]))
            sb.append(
                "<text x=\"%.1f\" y=\"%.1f\" font-family=\"sans-serif\" font-size=\"%.0f\" fill=\"#3b362f\" text-anchor=\"middle\">%s</text>\n"
                    .format(java.util.Locale.ROOT, c[0], c[1] + size + 2, size, escape(place.name)),
            )
        }

        if (title.isNotEmpty()) {
            sb.append(
                "<text x=\"8\" y=\"20\" font-family=\"sans-serif\" font-size=\"14\" fill=\"#3b362f\">%s</text>\n"
                    .format(escape(title)),
            )
        }
        sb.append(
            "<text x=\"8\" y=\"%.0f\" font-family=\"sans-serif\" font-size=\"11\" fill=\"#6b655c\">%s</text>\n"
                .format(java.util.Locale.ROOT, heightPx - 8, escape(KarteIndex.ATTRIBUTION)),
        )
        sb.append("</svg>\n")
        return sb.toString()
    }

    private fun appendFeature(sb: StringBuilder, view: MapView, f: MapFeature, fill: Boolean) {
        val (width, colour) = STYLE[f.cls] ?: return
        if (f.size < 2) return
        sb.append(if (fill) "<polygon fill=\"$colour\" stroke=\"none\" points=\"" else "<polyline fill=\"none\" stroke=\"$colour\" stroke-width=\"$width\" points=\"")
        for (i in 0 until f.size) {
            val c = view.canvas(LatLon(f.lat[i], f.lon[i]))
            sb.append("%.1f,%.1f ".format(java.util.Locale.ROOT, c[0], c[1]))
        }
        sb.append("\"/>\n")
    }

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** The preview reads a whole region at once, so it wants a bigger cache than
     *  a phone screen does. */
    private const val PREVIEW_TILE_CACHE = 4096
}
