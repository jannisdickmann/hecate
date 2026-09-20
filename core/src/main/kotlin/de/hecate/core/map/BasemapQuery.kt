package de.hecate.core.map

import de.hecate.core.Tuning

/**
 * A small LRU of decoded tiles.
 *
 * Shared between the views, which is why [BasemapQuery] returns lat/lon rather
 * than pixels: one tile is read by a route drawing, the heatmap card and the
 * full-screen view, and those have three different projections between them.
 *
 * Not thread-safe by itself; every caller here holds it on one background
 * dispatcher.
 */
class TileCache(private val maxTiles: Int = Tuning.MAP_TILE_CACHE) {

    private val entries = LinkedHashMap<Key, Tile?>(32, 0.75f, true)

    private data class Key(val level: Level, val id: TileId)

    /**
     * The tile, loading it through [load] on a miss.
     *
     * A miss that finds nothing is cached too — as a null — because "there is
     * no map here" is the common answer for a route abroad, and re-asking the
     * filesystem for it on every pan is the one cost this cache exists to
     * avoid.
     */
    fun get(level: Level, id: TileId, load: (Level, TileId) -> Tile?): Tile? {
        val key = Key(level, id)
        if (entries.containsKey(key)) return entries[key]
        val tile = load(level, id)
        entries[key] = tile
        while (entries.size > maxTiles) {
            val eldest = entries.keys.firstOrNull() ?: break
            entries.remove(eldest)
        }
        return tile
    }

    fun clear() = entries.clear()

    val size: Int get() = entries.size
}

/**
 * Viewport plus level of detail: which ground, at which density.
 *
 * The level of detail is driven by **dp per metre, not pixels per metre**. A 3x
 * screen must not show a denser map than a 1x one at the same apparent size —
 * the same bug `HEATMAP_CELL_M` exists to prevent one layer down — and a
 * threshold in pixels would do exactly that.
 *
 * With a ~360 dp canvas the bands in [Tuning] read straight off as "how wide is
 * this view":
 *
 * | from | view ≈ | turns on |
 * |---|---|---|
 * | 0 | any | water, rivers, rail, motorway, primary; city labels |
 * | `MAP_LOD_TOWN` | ≤ 45 km | town labels |
 * | `MAP_LOD_STREETS` | ≤ 12 km | minor roads; village labels; **patch off** |
 * | `MAP_LOD_SERVICE` | ≤ 4.5 km | service roads and field tracks |
 * | `MAP_LOD_HAMLET` | ≤ 3.6 km | hamlet and suburb labels |
 * | `MAP_LOD_PATH` | ≤ 2.4 km | paths and footways |
 *
 * Against the real views: a 12 km route in a 220 dp `PolylineView` is about
 * 0.03 dp/m — streets just on, field tracks off. A 4 km loop is about 0.09,
 * which is streets and tracks, the picture a runner wants. The full-screen
 * heatmap over 40 km is about 0.009 — motorways, rivers, rail and town names —
 * and at eight times zoom about 0.07, a full street grid.
 *
 * Every band is a floor except one. The faint urban patch **closes** at
 * `MAP_LOD_STREETS`: at whole-region zoom "where are the towns" is the useful
 * question, and the moment the streets themselves are drawn the patch only
 * muddies them.
 */
object BasemapQuery {

    /** Which classes are drawn at this density. */
    fun classesAt(dpPerM: Double): Set<MapClass> {
        val out = LinkedHashSet<MapClass>()
        out.add(MapClass.WATER_AREA)
        out.add(MapClass.WATERWAY)
        out.add(MapClass.RAIL)
        out.add(MapClass.MOTORWAY)
        out.add(MapClass.PRIMARY)
        if (dpPerM >= Tuning.MAP_LOD_STREETS) out.add(MapClass.MINOR)
        if (dpPerM >= Tuning.MAP_LOD_SERVICE) out.add(MapClass.SERVICE)
        if (dpPerM >= Tuning.MAP_LOD_PATH) out.add(MapClass.PATH)
        return out
    }

    /** Which settlements are named at this density. */
    fun placesAt(dpPerM: Double): Set<PlaceClass> {
        val out = LinkedHashSet<PlaceClass>()
        out.add(PlaceClass.CITY)
        if (dpPerM >= Tuning.MAP_LOD_TOWN) out.add(PlaceClass.TOWN)
        if (dpPerM >= Tuning.MAP_LOD_STREETS) out.add(PlaceClass.VILLAGE)
        if (dpPerM >= Tuning.MAP_LOD_HAMLET) {
            out.add(PlaceClass.HAMLET)
            out.add(PlaceClass.SUBURB)
        }
        return out
    }

    /** The one band that is a ceiling rather than a floor. */
    fun urbanPatchAt(dpPerM: Double): Boolean = dpPerM < Tuning.MAP_LOD_STREETS

    /** Which stored level answers this density. Below the street band nothing
     *  in `fein` would be drawn, so the coarse tiles are the whole answer and
     *  are a seventh of the bytes. */
    fun levelAt(dpPerM: Double): Level =
        if (dpPerM >= Tuning.MAP_LOD_STREETS) Level.FEIN else Level.GROB

    /**
     * The scene inside [bounds] at [dpPerM].
     *
     * Features are already clipped to their tiles, so nothing here tests a
     * bounding box: a tile that overlaps the view contributes everything it
     * holds of the classes that are on, and a tile that does not is never read.
     *
     * A view over ground that was never fetched returns [MapScene.EMPTY] and the
     * track is drawn on empty ground — exactly the picture this app had before
     * the basemap existed, which is the right thing for a holiday in a region
     * nobody has downloaded.
     */
    fun query(store: TileStore, bounds: LatLonBounds, dpPerM: Double, cache: TileCache): MapScene {
        val level = levelAt(dpPerM)
        val classes = classesAt(dpPerM)
        val places = placesAt(dpPerM)
        val wantPatch = urbanPatchAt(dpPerM)

        val features = ArrayList<MapFeature>()
        val labels = ArrayList<MapPlace>()
        val patches = ArrayList<MapFeature>()
        val load = { l: Level, t: TileId -> store.readOrDiscard(l, t) }
        for (id in TileGrid.tilesIn(bounds)) {
            val exact = cache.get(level, id, load)
            // A coarse tile is a legitimate stand-in while the detailed one is
            // still being fetched: fewer roads is a quieter map, not a wrong
            // one.
            val fallback = if (exact == null && level == Level.FEIN) cache.get(Level.GROB, id, load) else null
            val tile = exact ?: fallback ?: continue
            for (f in tile.features) if (f.cls in classes) features.add(f)
            for (p in tile.places) if (p.cls in places) labels.add(p)
            if (wantPatch) patches.addAll(tile.urban)
        }
        if (features.isEmpty() && labels.isEmpty() && patches.isEmpty()) return MapScene.EMPTY
        // Back to front, so a motorway is never interrupted by the footpath
        // that crosses it.
        features.sortBy { it.cls.drawOrder }
        return MapScene(features, labels, patches)
    }
}
