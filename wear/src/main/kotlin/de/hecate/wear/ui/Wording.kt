package de.hecate.wear.ui

import de.hecate.core.display.HealthKind
import de.hecate.core.display.RecordingHealth
import de.hecate.core.display.RouteBelief
import de.hecate.core.display.RouteBeliefKind

/**
 * The German words for the two decisions taken in `:core`.
 *
 * Here rather than in the decision so that the screen and the Ongoing Activity
 * notification cannot drift apart — they used to compute their own titles from
 * the match phase, in two places, with two slightly different ideas of what to
 * say (§4: no localization, single user).
 *
 * Every string is chosen against one rule: **the route line never sounds like a
 * malfunction**. `Suche…` did, and "Unbekannt" did, and neither was one.
 */
object Wording {

    /**
     * The route line.
     *
     * [RouteBeliefKind.LEADING] is prefixed with `≈` rather than dressed up in
     * words: it is the same name that will still be there once it commits, and a
     * one-character hedge keeps the name itself readable at a glance and at a
     * width two names have to share.
     */
    fun route(belief: RouteBelief): String = when (belief.kind) {
        RouteBeliefKind.COMMITTED, RouteBeliefKind.ASSUMED -> belief.name ?: "Route"
        RouteBeliefKind.LEADING -> "≈ ${belief.name}"
        RouteBeliefKind.CONTENDERS ->
            "${short(belief.name)} · ${short(belief.alternative)}"
        // Not "Suche…": the app is not looking for something it has lost, it is
        // comparing what it has against the routes it knows.
        RouteBeliefKind.COMPARING -> "Noch am Vergleichen"
        // Not "Unbekannt". The null hypothesis winning is a finding — this is not
        // one of your routes — and one with a future: run it a few more times and
        // it becomes one.
        RouteBeliefKind.NEW_ROUTE -> "Neue Strecke"
        RouteBeliefKind.NO_ROUTES -> "Noch keine Routen"
        RouteBeliefKind.MATCHER_OFF -> "Routenvergleich aus"
    }

    /** The health line. On the smaller watch a healthy recording is left to the
     *  marker beside it and this text is not drawn at all; the live page's
     *  layout profile decides. */
    fun health(health: RecordingHealth): String = when (health.kind) {
        HealthKind.RECORDING -> "Aufzeichnung"
        HealthKind.PREPARING -> "GPS wird gesucht"
        HealthKind.PAUSED -> "Pausiert"
        HealthKind.AUTO_PAUSED -> "Auto-Pause"
        HealthKind.OFF_ROUTE -> "Abweichung"
        HealthKind.NO_LOCATION_PERMISSION -> "Ohne GPS – Zeit und Puls"
        HealthKind.POOR_FIX -> "GPS ungenau"
        // With the duration, because "how long has this been going on" is the
        // whole question: ten seconds under a bridge is not thirty minutes with
        // the receiver dead.
        HealthKind.NO_FIX -> "Kein GPS seit ${Format.duration(health.fixAgeMs)}"
        HealthKind.NOT_WRITING -> "Keine Aufzeichnung"
        HealthKind.MATCHER_OFF -> "Routenvergleich aus"
        HealthKind.MESSAGE -> health.message ?: ""
    }

    /** Two route names have to share one line on a 41 mm face, so each gets
     *  half of it. Cut on a word boundary where there is one. */
    private fun short(name: String?): String {
        val n = name ?: return "?"
        if (n.length <= MAX_SHARED_NAME) return n
        val cut = n.take(MAX_SHARED_NAME)
        val space = cut.lastIndexOf(' ')
        return if (space >= MAX_SHARED_NAME / 2) cut.take(space) else cut.trimEnd() + "…"
    }

    private const val MAX_SHARED_NAME = 11
}
