package de.hecate.core.model

/**
 * Free-text activity names, as exports write them, to the types this app records.
 *
 * Both importers need this and they need it to agree: a Google Health takeout
 * calls the same workout "Bike" in one export and "Outdoor Bike" or "Ride" in
 * another, a TCX from the same watch says `Sport="Biking"`, and a TCX from
 * Garmin says things the TCX schema never promised. One table, so that a name
 * the takeout understands cannot be a name the TCX reader silently gets wrong.
 *
 * Three rules hold it together.
 *
 * **Keyword matching, not a table of exact names.** The exports are not
 * consistent between the phone app and the watch, let alone between vendors, and
 * a list of exact spellings would be out of date by the next export.
 *
 * **Order decides, first match wins.** Several of these words contain each
 * other, and the containment runs the wrong way: `Langlauf` ends in "lauf" and
 * is not a run, `Skitourengehen` contains "geh" and is not a walk, `Mountainbike`
 * contains "mountain" and is not a hike. The order below is therefore load
 * bearing — most specific first — and [UNSUPPORTED] gets the first word of all.
 *
 * **Anything unmatched is null, and the caller says so out loud.** Filing a ski
 * descent under "running" would poison the clusterer with a route nobody can
 * ever run, so a name this table does not know is reported to the user, who can
 * force it with `--map` if they disagree.
 */
object ActivityNames {

    /**
     * Names that must not match anything, checked before the table proper.
     *
     * Two kinds, and they fail for the same reason: there is no route in them.
     *
     * The indoor variants share every root the outdoor ones do — `Laufband` is a
     * "lauf", `Indoor Cycling` is a "cycl" — and would be filed as a run or a
     * ride with no positions to show for it. The look-alikes are worse: `Eislaufen`
     * and `Rollschuhlaufen` both end in "lauf", and `Wasserski` is not a `Ski`.
     * These are real sports that this app does not model, and reporting them is
     * the honest answer.
     */
    private val UNSUPPORTED = listOf(
        // Indoor: recognisable, but there is nothing for the matcher to work on.
        "indoor", "laufband", "treadmill", "stationary", "heimtrainer",
        "ergometer", "spinning", "rolle",
        // Look-alikes that the keywords below would otherwise swallow.
        "wasserski", "waterski", "jetski", "eislauf", "schlittschuh",
        "rollschuh", "inline",
    )

    /**
     * Keyword to type, in the order they are tried.
     *
     * Substrings of a lowercased name, so "bik" covers `Bike`, `Biking` and
     * `Mountainbiking` at once, and "wander" covers `Wandern`, `Bergwandern`
     * and `Wanderung`.
     */
    private val KEYWORDS: List<Pair<String, ExerciseType>> = listOf(
        // Skiing first: "langlauf" would read as a run and "skitourengehen" as a
        // walk if anything got to them earlier.
        "langlauf" to ExerciseType.SKIING,
        "ski" to ExerciseType.SKIING,
        "schi" to ExerciseType.SKIING,
        "piste" to ExerciseType.SKIING,
        "abfahrt" to ExerciseType.SKIING,

        // "bik" covers Bike, Biking and Mountainbiking alike. There is
        // deliberately no bare "mountain" keyword for hiking: it would take
        // Mountainbiking with it.
        "bik" to ExerciseType.BIKING,
        "mtb" to ExerciseType.BIKING,
        "cycl" to ExerciseType.BIKING,
        "pedelec" to ExerciseType.BIKING,
        "velo" to ExerciseType.BIKING,
        "gravel" to ExerciseType.BIKING,
        "rad" to ExerciseType.BIKING,
        "ride" to ExerciseType.BIKING,

        // Before running: `Wanderung` ends in "rung", and "run" is in there.
        // Snowshoeing is walking uphill in snow and leaves a route like any
        // other; Health Connect files it separately, this app files it as a hike.
        "hik" to ExerciseType.HIKING,
        "wander" to ExerciseType.HIKING,
        "trekking" to ExerciseType.HIKING,
        "bergtour" to ExerciseType.HIKING,
        "bergsteig" to ExerciseType.HIKING,
        "schneeschuh" to ExerciseType.HIKING,
        "snowshoe" to ExerciseType.HIKING,

        "run" to ExerciseType.RUNNING,
        "jog" to ExerciseType.RUNNING,
        "lauf" to ExerciseType.RUNNING,
        "marathon" to ExerciseType.RUNNING,

        "walk" to ExerciseType.WALKING,
        "geh" to ExerciseType.WALKING,
        "spazier" to ExerciseType.WALKING,
        "stroll" to ExerciseType.WALKING,
    )

    /** The type [activityName] describes, or null if this app does not record it. */
    fun typeOf(activityName: String?): ExerciseType? {
        val n = activityName?.lowercase()?.trim() ?: return null
        if (n.isEmpty()) return null
        if (UNSUPPORTED.any { n.contains(it) }) return null
        return KEYWORDS.firstOrNull { (keyword, _) -> n.contains(keyword) }?.second
    }
}
