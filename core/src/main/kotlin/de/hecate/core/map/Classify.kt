package de.hecate.core.map

import de.hecate.core.Tuning

/**
 * What an OpenStreetMap tag set becomes on this map, and — just as important —
 * what it does not.
 *
 * One table in one file, so that "what is on the map" is something you read
 * rather than something you reconstruct by following the builder around.
 *
 * ### What is dropped, and why
 *
 * - **Buildings.** The largest class in German OSM by a wide margin, invisible
 *   at any zoom where a 20 km track fits on a phone, and on their own enough to
 *   double every file.
 * - **Landuse and woodland polygons.** Area fills compete with the track for
 *   contrast, which is the opposite of what a backdrop is for.
 * - **POIs, addresses, administrative boundaries, power lines.** None of them
 *   answer "where was this".
 * - **`footway=sidewalk` and `footway=crossing`.** In a German town these
 *   outnumber the streets they run beside, and they draw a second, fuzzier copy
 *   of the same street grid.
 * - **Tracks and paths under [Tuning.MAP_MIN_PATH_M].** A stub is noise.
 * - **Street names.** Place names tell you where you are; street names on a dim
 *   backdrop are clutter, and they would cost roughly as much as the geometry
 *   that carries them.
 */
object Classify {

    private val RAIL_VALUES = setOf("rail", "light_rail", "narrow_gauge", "tram", "subway")
    private val DEAD_RAIL = setOf("abandoned", "disused", "razed", "construction", "proposed")
    private val YARD_SERVICE = setOf("yard", "siding", "spur")

    /**
     * The class of a way, or null when it is not drawn.
     *
     * [lengthM] and [areaM2] are the way's own measurements; the callers have
     * them already and the size thresholds above cannot be applied without them.
     */
    fun wayClass(tags: Map<String, String>, lengthM: Double, areaM2: Double): MapClass? {
        tags["natural"]?.let { if (it == "water") return waterArea(areaM2) }
        tags["landuse"]?.let { if (it == "reservoir" || it == "basin") return waterArea(areaM2) }
        tags["waterway"]?.let { w ->
            when (w) {
                "riverbank" -> return waterArea(areaM2)
                "river", "canal" -> return MapClass.WATERWAY
                "stream" -> return if (lengthM >= Tuning.MAP_MIN_STREAM_M) MapClass.WATERWAY else null
                else -> Unit
            }
        }
        tags["railway"]?.let { r ->
            if (r !in RAIL_VALUES) return null
            // A line lifted in 1974 is not a landmark, and OSM remembers it
            // both ways round: as a value, which RAIL_VALUES has already
            // rejected, and as an `abandoned:railway=rail` key prefix, which it
            // has not.
            if (tags["railway:state"] in DEAD_RAIL) return null
            if (tags.keys.any { k -> DEAD_RAIL.any { k.startsWith("$it:") } }) return null
            if (tags["service"] in YARD_SERVICE) return null
            return MapClass.RAIL
        }
        val highway = tags["highway"] ?: return null
        return when (highway) {
            "motorway", "trunk", "motorway_link", "trunk_link" -> MapClass.MOTORWAY
            "primary", "secondary", "primary_link", "secondary_link" -> MapClass.PRIMARY
            "tertiary", "tertiary_link", "unclassified", "residential", "living_street", "pedestrian" ->
                MapClass.MINOR
            "service", "track" ->
                if (lengthM >= Tuning.MAP_MIN_PATH_M) MapClass.SERVICE else null
            "path", "footway", "cycleway", "bridleway", "steps" -> {
                // The two that pave every German town twice over.
                if (tags["footway"] == "sidewalk" || tags["footway"] == "crossing") null
                else if (lengthM >= Tuning.MAP_MIN_PATH_M) MapClass.PATH
                else null
            }
            else -> null
        }
    }

    private fun waterArea(areaM2: Double): MapClass? =
        if (areaM2 >= Tuning.MAP_MIN_WATER_AREA_M2) MapClass.WATER_AREA else null

    /** The class of a `place=*` node with a name, or null. */
    fun placeClass(tags: Map<String, String>): PlaceClass? = when (tags["place"]) {
        "city" -> PlaceClass.CITY
        "town" -> PlaceClass.TOWN
        "village" -> PlaceClass.VILLAGE
        "suburb", "borough", "quarter" -> PlaceClass.SUBURB
        "hamlet" -> PlaceClass.HAMLET
        else -> null
    }

    /** OSM writes populations as "112 000" and "1,234" often enough to matter. */
    fun population(raw: String?): Int =
        raw?.filter { it.isDigit() }?.take(9)?.toIntOrNull() ?: 0
}
