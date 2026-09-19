package de.hecate.core.display

import de.hecate.core.Tuning
import de.hecate.core.model.MatchPhase
import de.hecate.core.model.MatchState

/**
 * What the watch may say about the route, decided once and in one place.
 *
 * The rule the old display broke: **what the matcher knows and whether the
 * recording is working are two different questions**, and only the second one is
 * ever allowed to look like a problem. "Suche…" in warning orange, next to
 * "GPS…" in the same orange, told the user something was wrong at the exact
 * moment the app was working perfectly and simply had not finished deciding.
 * Worse, it threw away an answer: the matcher carries a weighted, named
 * candidate list from the first sample — seeded from how often each route is
 * taken at this hour on this day — and the screen said nothing about it.
 *
 * So every state here names something the app *does* know, and the two states
 * that used to sound like failures no longer do:
 *
 *  - `Suche…` becomes the leading route's name, or the names it is choosing
 *    between, or at worst [COMPARING] — a description of work in progress, not
 *    of ignorance.
 *  - `Unbekannt` becomes [NEW_ROUTE]: not "I don't know where you are" but "this
 *    is not one of your routes", which is a finding about the world, true, and
 *    the first step towards the route being learned.
 *
 * Lives in `:core` because it is a decision, and decisions are tested on a
 * laptop; `:wear` supplies the German words and the colours for each kind.
 */
enum class RouteBeliefKind {
    /** One route, worked out from today's track. The strongest claim there is. */
    COMMITTED,

    /** One route, taken from the user's own ranking because the evidence could
     *  not choose between routes running over the same ground. A setting, not a
     *  finding, and coloured differently for that reason. */
    ASSUMED,

    /** No commitment, but one route holds most of the belief. Named, and marked
     *  as the guess it is. */
    LEADING,

    /** Two or more routes are still indistinguishable. Both names are shown:
     *  "it is one of these" is information, and a far better answer than a
     *  spinner. */
    CONTENDERS,

    /** Candidates exist but none stands out yet — the first minutes, usually. */
    COMPARING,

    /** The null hypothesis dominates: today's track is not one of the learned
     *  routes. */
    NEW_ROUTE,

    /** The bundle holds no route of this exercise type at all. A fact about the
     *  phone, not about this workout, and worth distinguishing. */
    NO_ROUTES,

    /** The matcher gave up (US-1). Route-derived numbers must go with it: the
     *  old display left them on screen, frozen, with an arrival time that kept
     *  drifting later. */
    MATCHER_OFF,
}

/**
 * The route line's content. [name] and [alternative] are already-resolved route
 * names, so this type stays free of the bundle.
 */
data class RouteBelief(
    val kind: RouteBeliefKind,
    val name: String? = null,
    val alternative: String? = null,
    val contenderCount: Int = 0,
) {
    /** The name shown is the app's best guess rather than its conclusion, and
     *  must be marked as such wherever it appears. */
    val isProvisional: Boolean get() = kind == RouteBeliefKind.LEADING

    /** Whether route-relative numbers (remaining distance, arrival, progress)
     *  may be shown at all. They are meaningless without one specific route. */
    val hasRoute: Boolean get() = kind == RouteBeliefKind.COMMITTED ||
        kind == RouteBeliefKind.ASSUMED ||
        kind == RouteBeliefKind.LEADING
}

/**
 * Map one belief to one line of display.
 *
 * [nameOf] resolves a route id to the name the phone gave it; a route whose name
 * is missing falls back to the kind below it, so a bundle in an odd state
 * degrades to "comparing" rather than to a blank headline.
 */
fun routeBelief(
    state: MatchState,
    matcherDisabled: Boolean,
    nameOf: (Long) -> String?,
): RouteBelief {
    if (matcherDisabled || state.disabled) return RouteBelief(RouteBeliefKind.MATCHER_OFF)
    if (state.noRoutesKnown) return RouteBelief(RouteBeliefKind.NO_ROUTES)

    if (state.phase == MatchPhase.COMMITTED) {
        val name = state.committedRouteId?.let(nameOf)
        if (name != null) {
            return RouteBelief(
                kind = if (state.committedByPriority) RouteBeliefKind.ASSUMED else RouteBeliefKind.COMMITTED,
                name = name,
            )
        }
    }

    if (state.phase == MatchPhase.UNKNOWN) return RouteBelief(RouteBeliefKind.NEW_ROUTE)

    // Two or more routes over the same ground explain every sample equally well,
    // so naming one of them would be a coin toss dressed up as a finding — the
    // same reason the matcher itself refuses to commit here. Say that it is one
    // of them instead, which is both true and useful.
    val contenders = state.contenderCount
    if (contenders >= 2) {
        val names = state.candidates.asSequence().mapNotNull { nameOf(it.routeId) }.take(2).toList()
        if (names.size == 2) {
            return RouteBelief(
                kind = RouteBeliefKind.CONTENDERS,
                name = names[0],
                alternative = names[1],
                contenderCount = contenders,
            )
        }
        return RouteBelief(RouteBeliefKind.COMPARING, contenderCount = contenders)
    }

    val leaderName = state.leaderRouteId?.let(nameOf)
    if (leaderName != null && state.leaderWeight >= Tuning.LEADER_MIN_WEIGHT) {
        return RouteBelief(RouteBeliefKind.LEADING, name = leaderName)
    }
    return RouteBelief(RouteBeliefKind.COMPARING, contenderCount = contenders)
}
