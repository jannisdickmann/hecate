package de.hecate.core.map.overpass

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * As much of the Overpass `out geom` response as this app reads, and no more.
 *
 * `ignoreUnknownKeys` is doing real work here: a response carries `version`,
 * `generator`, `osm3s`, per-element `timestamp`, `changeset`, `bounds` and
 * whatever the next release of Overpass adds. Declaring them to ignore them
 * would be a list to keep in step with somebody else's software.
 */
@Serializable
data class OsmPoint(val lat: Double, val lon: Double)

@Serializable
data class OsmElement(
    val type: String = "",
    val id: Long = 0L,
    /** Nodes carry their position directly. */
    val lat: Double? = null,
    val lon: Double? = null,
    /** Ways carry theirs in `geometry`, which is what `out geom` adds and what
     *  spares a second round trip to resolve node ids. */
    val geometry: List<OsmPoint>? = null,
    /** Present with `out geom`; the first and last being equal is how a closed
     *  way announces itself. */
    val nodes: List<Long>? = null,
    val tags: Map<String, String> = emptyMap(),
)

@Serializable
data class OsmResponse(
    val elements: List<OsmElement> = emptyList(),
    @SerialName("remark") val remark: String? = null,
)

object OsmJson {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * Parse a response body.
     *
     * Overpass reports a timeout or an out-of-memory as a **200 with a
     * `remark`** and an empty or partial element list, which is the one failure
     * mode that would otherwise be stored as "this ground has no roads on it"
     * and never fetched again. So a remark is an exception, not a log line.
     */
    fun parse(body: String): OsmResponse {
        val parsed = try {
            json.decodeFromString(OsmResponse.serializer(), body)
        } catch (e: Exception) {
            throw OverpassException("Antwort ist kein Overpass-JSON: ${e.message}", e)
        }
        parsed.remark?.let { remark ->
            if (remark.contains("error", ignoreCase = true) ||
                remark.contains("timed out", ignoreCase = true) ||
                remark.contains("out of memory", ignoreCase = true)
            ) {
                throw OverpassException("Overpass meldet: $remark")
            }
        }
        return parsed
    }
}
