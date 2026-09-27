package de.hecate.phone.archive

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.room.withTransaction
import de.hecate.core.Tuning
import de.hecate.core.geo.Track
import de.hecate.core.io.BundleCodec
import de.hecate.core.model.Altitudes
import de.hecate.core.model.HeartRates
import de.hecate.core.model.MatchLogEntry
import de.hecate.core.model.SessionEvent
import de.hecate.core.model.RecordedSession
import de.hecate.core.model.Sample
import de.hecate.core.model.SessionEndReason
import de.hecate.core.model.SessionFooter
import de.hecate.core.model.SessionHeader
import de.hecate.core.model.SessionSource
import de.hecate.core.archive.Archive
import de.hecate.core.archive.ArchiveCodec
import de.hecate.phone.db.HecateDatabase
import de.hecate.phone.db.MatchLogEntity
import de.hecate.phone.db.SessionEventsEntity
import de.hecate.phone.db.SessionSeriesEntity
import de.hecate.phone.db.MetaEntity
import de.hecate.phone.db.RouteEntity
import de.hecate.phone.db.RouteMemberEntity
import de.hecate.phone.db.RoutePointEntity
import de.hecate.phone.db.RouteProfileEntity
import de.hecate.phone.db.SampleEntity
import de.hecate.phone.db.SessionEntity
import de.hecate.phone.io.CacheFile
import de.hecate.phone.work.Jobs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Moves the database in and out of the backup archive (§1.3 of the open points).
 *
 * The ZIP itself is [ArchiveCodec]'s job; everything here is the mapping between
 * Room rows and the archive's own DTOs, written out by hand so that renaming a
 * column cannot silently change the backup format.
 *
 * The user picks the destination and the source through the Storage Access
 * Framework, so the app needs no storage permission and holds no opinion about
 * where the file lives. What happens to it afterwards — Drive, a cable, nothing
 * at all — is not this app's business, and NFR-7 is untouched either way.
 */
class ArchiveRepository(
    private val context: Context,
    private val db: HecateDatabase,
) {

    // ------------------------------------------------------------------ export

    /**
     * Write the whole database to [uri] as a ZIP. One session is held in memory
     * at a time, so the cost does not grow with the size of the history.
     */
    suspend fun export(uri: Uri): Archive.ExportReport = withContext(Dispatchers.IO) {
        val sessions = db.sessions().all()
        val routes = db.routes().all()

        val dump = Archive.Database(
            sessions = sessions.map { it.toArchive() },
            routes = routes.map { it.toArchive() },
            routeMembers = db.routes().allMembers().map { Archive.RouteMember(it.routeId, it.sessionId) },
            routePoints = routes.flatMap { r ->
                db.routes().points(r.id).map {
                    Archive.RoutePoint(it.routeId, it.seq, it.lat, it.lon, it.cumDistM)
                }
            },
            routeProfile = routes.flatMap { r ->
                db.routes().profile(r.id).map {
                    Archive.ProfileBin(
                        it.routeId, it.bin, it.medianCumTimeS, it.p25S, it.p75S, it.medianHr, it.medianAltM,
                    )
                }
            },
            meta = db.meta().all().map { Archive.Meta(it.key, it.value) },
        )

        val manifest = Archive.Manifest(
            formatVersion = Archive.FORMAT_VERSION,
            exportedAtUtcMs = System.currentTimeMillis(),
            appVersion = appVersion(),
            sessionCount = sessions.size,
            routeCount = routes.size,
        )

        val stream = context.contentResolver.openOutputStream(uri)
            ?: throw Archive.ArchiveException("Datei konnte nicht geschrieben werden")

        var bytes = 0L
        stream.use { out ->
            val writer = ArchiveCodec.Writer(out)
            writer.use {
                it.writeManifest(manifest)
                it.writeDatabase(dump)
                for (session in sessions) it.writeSession(recordedSession(session))
            }
            // Read after close(): the deflater only flushes its last block then.
            bytes = writer.bytesWritten
        }

        Log.i(TAG, "Sicherung geschrieben: ${sessions.size} Sitzungen, ${routes.size} Routen, $bytes B")
        Archive.ExportReport(sessions.size, routes.size, bytes)
    }

    /**
     * Rebuild one session in the watch's own shape, so that the file inside the
     * archive is the file the replay harness reads.
     *
     * Three things cannot be round-tripped, because the phone never stored them:
     * `bundleVersion` and `appVersion` are dropped at ingest, and
     * `SessionEndReason` is not a column at all. The footer therefore says
     * [SessionEndReason.IMPORTED] — true of the file being read, rather than a
     * guess at how the original recording ended.
     *
     * The end time is a real column since schema v2, so it is used as stored.
     * Rows written before v2 have NULL there and fall back to the last sample,
     * which is what the session end used to be taken from — and which is wrong
     * by however long the session spent paused.
     */
    private suspend fun recordedSession(session: SessionEntity): RecordedSession {
        val samples = db.sessions().samples(session.id).map {
            Sample(
                tOffsetMs = it.tOffsetMs,
                activeMs = it.activeMs,
                lat = it.lat,
                lon = it.lon,
                altM = it.altM,
                hrBpm = it.hrBpm,
                accuracyM = it.accuracyM,
                paused = it.paused,
            )
        }
        val matchLog = db.sessions().matchLog(session.id)?.let { row ->
            runCatching { BundleCodec.decodeMatchLog(row.payload.toString(Charsets.UTF_8)) }
                .getOrDefault(emptyList())
        } ?: emptyList()
        // Empty for anything ingested before schema v3, which had nowhere to keep
        // these. ArchiveCodec has always written an events section; until now it
        // was always writing nothing into it.
        val events = db.sessions().events(session.id)?.let { row ->
            runCatching { BundleCodec.decodeEvents(row.payload.toString(Charsets.UTF_8)) }
                .getOrDefault(emptyList())
        } ?: emptyList()
        // The sensors' own series, from schema v8. Before it they were merged
        // into the sample columns and discarded, so a backup of an older session
        // carries the merged result and nothing to re-derive it from — which is
        // exactly the loss the table was added to stop.
        val series = db.sessions().series(session.id)
        val rates = series?.heartRates?.let { blob ->
            runCatching { BundleCodec.decodeRates(blob.toString(Charsets.UTF_8)) }
                .getOrDefault(emptyList())
        } ?: emptyList()
        val altitudes = series?.altitudes?.let { blob ->
            runCatching { BundleCodec.decodeReadings(blob.toString(Charsets.UTF_8)) }
                .getOrDefault(emptyList())
        } ?: emptyList()
        val speeds = series?.speeds?.let { blob ->
            runCatching { BundleCodec.decodeReadings(blob.toString(Charsets.UTF_8)) }
                .getOrDefault(emptyList())
        } ?: emptyList()

        return RecordedSession(
            header = SessionHeader(
                sessionId = session.id,
                startedAtUtcMs = session.startedAtUtc,
                tzOffsetMinutes = session.tzOffsetMinutes,
                exerciseType = session.exerciseType,
                source = session.source,
            ),
            samples = samples,
            events = events,
            heartRates = rates,
            altitudes = altitudes,
            speeds = speeds,
            footer = SessionFooter(
                endedAtUtcMs = session.endedAtUtc
                    ?: (session.startedAtUtc + (samples.lastOrNull()?.tOffsetMs ?: 0L)),
                activeDurationMs = session.activeDurationMs,
                totalDistanceM = session.totalDistanceM,
                reason = SessionEndReason.IMPORTED,
            ),
            matchLog = matchLog,
        )
    }

    // ----------------------------------------------------------------- restore

    /**
     * Read [uri] back into the database.
     *
     * Merges rather than replaces: a session or route already present by id is
     * left exactly as it is and counted as skipped. Restoring into an empty
     * database — the case this feature exists for — is therefore exact, and
     * restoring into a populated one can never destroy what is already there.
     *
     * Sessions are written row for row rather than pushed back through
     * [de.hecate.phone.ingest.SessionRepository.ingest]. Ingest would reset
     * `hcWriteState` to PENDING and re-derive `committedAtM`, and it applies the
     * watch's start-time duplicate heuristic — which has no business second
     * guessing a backup of the app's own recordings.
     *
     * It has every business second guessing an *import*, though, which is what
     * [isDuplicateImport] is for.
     */
    suspend fun restore(uri: Uri): Archive.RestoreReport = withContext(Dispatchers.IO) {
        // ZIP needs random access and a `content://` Uri is a one-shot stream,
        // so the archive is copied into the cache first and deleted afterwards.
        // [CacheFile] rather than a private copy of it: „Karte einlesen" needs
        // exactly the same thing (A.29), and two copies of a temp-file dance is
        // two places to leak one.
        val temp = CacheFile.copy(context, uri, "hecate-restore")
        try {
            ArchiveCodec.Reader(temp).use { reader -> restoreFrom(reader) }
        } catch (e: Archive.ArchiveException) {
            throw e
        } catch (e: Exception) {
            throw Archive.ArchiveException("Sicherung konnte nicht gelesen werden: ${e.message}", e)
        } finally {
            temp.delete()
        }
    }

    private suspend fun restoreFrom(reader: ArchiveCodec.Reader): Archive.RestoreReport {
        val dump = reader.database

        var restored = 0
        var skipped = 0
        var failed = 0
        val messages = ArrayList<String>()

        // One transaction per session rather than one for the whole archive: a
        // thousand sessions' worth of samples in a single transaction is a lot to
        // hold for an operation the user is watching.
        for (row in dump.sessions) {
            try {
                if (db.sessions().countById(row.id) > 0 || isDuplicateImport(row)) {
                    skipped++
                    continue
                }
                val session = reader.session(row.id)
                if (session == null || session.samples.isEmpty()) {
                    failed++
                    messages.add("${row.id}: keine lesbaren Messpunkte im Archiv")
                    continue
                }
                db.withTransaction {
                    db.sessions().insert(row.toEntity())
                    // The same join the live ingest does: an archive carries the
                    // two series exactly as the watch wrote them, so a restore
                    // has to put them together too. A session without a separate
                    // series comes through unchanged.
                    db.sessions().insertSamples(
                        Altitudes.merge(
                            HeartRates.merge(session.samples, session.heartRates),
                            session.altitudes,
                        ).map { it.toEntity(row.id) },
                    )
                    if (session.heartRates.isNotEmpty() ||
                        session.altitudes.isNotEmpty() ||
                        session.speeds.isNotEmpty()
                    ) {
                        db.sessions().upsertSeries(seriesEntity(row.id, session))
                    }
                    if (session.matchLog.isNotEmpty()) {
                        db.sessions().upsertMatchLog(matchLogEntity(row.id, session.matchLog))
                    }
                    if (session.events.isNotEmpty()) {
                        db.sessions().upsertEvents(eventsEntity(row.id, session.events))
                    }
                }
                restored++
            } catch (e: Exception) {
                failed++
                messages.add("${row.id}: ${e.message ?: "fehlgeschlagen"}")
                Log.w(TAG, "Sitzung ${row.id} konnte nicht wiederhergestellt werden", e)
            }
        }

        var routesRestored = 0
        var routesSkipped = 0
        val restoredRouteIds = HashSet<Long>()
        for (route in dump.routes) {
            if (db.routes().byId(route.id) != null) {
                routesSkipped++
                continue
            }
            db.routes().upsert(route.toEntity())
            restoredRouteIds.add(route.id)
            routesRestored++
        }

        // Members, geometry and profile belong to the routes just restored. Rows
        // for a route that was skipped are dropped: the local copy of that route
        // owns its own, and mixing the two would leave the geometry describing a
        // different shape from the members.
        db.withTransaction {
            dump.routeMembers.filter { it.routeId in restoredRouteIds }
                .map { RouteMemberEntity(it.routeId, it.sessionId) }
                .takeIf { it.isNotEmpty() }?.let { db.routes().insertMembers(it) }

            dump.routePoints.filter { it.routeId in restoredRouteIds }
                .map { RoutePointEntity(it.routeId, it.seq, it.lat, it.lon, it.cumDistM) }
                .takeIf { it.isNotEmpty() }?.let { db.routes().insertPoints(it) }

            dump.routeProfile.filter { it.routeId in restoredRouteIds }
                .map {
                    RouteProfileEntity(
                        it.routeId, it.bin, it.medianCumTimeS, it.p25S, it.p75S, it.medianHr, it.medianAltM,
                    )
                }
                .takeIf { it.isNotEmpty() }?.let { db.routes().insertProfile(it) }

            for (m in dump.meta) db.meta().put(MetaEntity(m.key, m.value))
        }

        // Geometry and profiles came back with the archive, so the routes are
        // usable immediately; this reconciles them against whatever else is in the
        // database — Appendix A.5 keeps the names and priorities just restored —
        // and pushes the watch a fresh bundle.
        if (restored > 0 || routesRestored > 0) Jobs.enqueueClustering(context)

        Log.i(TAG, "Sicherung eingelesen: $restored Sitzungen, $routesRestored Routen, $failed Fehler")
        return Archive.RestoreReport(restored, skipped, routesRestored, routesSkipped, failed, messages)
    }

    /**
     * Whether an imported session in the archive is a workout the database
     * already holds under some other id.
     *
     * Only asked of imports, and this is the whole reason [SessionSource.isImport]
     * exists. A backup of the app's own recordings is merged by id and nothing
     * else, so restoring one is exact. But an archive built from a Google Health
     * takeout contains the watch's own log of every workout — including the ones
     * Hecate recorded itself and wrote back to Health Connect, and the ones the
     * user had already imported by hand as TCX. Those arrive with an id derived
     * from Google's exercise id, which matches nothing, and only the clock can
     * tell that the 07:12 run in the archive is the 07:12 run in the database.
     *
     * [Track.likelyDuplicate] is the same test the ingest path applies to the
     * watch's own double recordings (§7). Being wrong in the cautious direction
     * costs one workout out of a backfill of hundreds; being wrong the other way
     * gives the clusterer two members for one journey and quietly inflates every
     * route's member count.
     */
    private suspend fun isDuplicateImport(row: Archive.Session): Boolean {
        if (!row.source.isImport) return false
        return db.sessions().near(row.startedAtUtc, Tuning.DUPLICATE_START_WINDOW_MS).any {
            Track.likelyDuplicate(
                it.startedAtUtc, it.activeDurationMs,
                row.startedAtUtc, row.activeDurationMs,
            )
        }
    }

    // ---------------------------------------------------------------- plumbing

    private fun appVersion(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: ""

    private companion object {
        const val TAG = "ArchiveRepository"

        fun matchLogEntity(sessionId: String, entries: List<MatchLogEntry>) = MatchLogEntity(
            sessionId = sessionId,
            payload = BundleCodec.encodeMatchLog(entries).toByteArray(Charsets.UTF_8),
        )

        fun eventsEntity(sessionId: String, events: List<SessionEvent>) = SessionEventsEntity(
            sessionId = sessionId,
            payload = BundleCodec.encodeEvents(events).toByteArray(Charsets.UTF_8),
        )

        fun seriesEntity(sessionId: String, session: RecordedSession) = SessionSeriesEntity(
            sessionId = sessionId,
            heartRates = session.heartRates
                .takeIf { it.isNotEmpty() }
                ?.let { BundleCodec.encodeRates(it).toByteArray(Charsets.UTF_8) },
            altitudes = session.altitudes
                .takeIf { it.isNotEmpty() }
                ?.let { BundleCodec.encodeReadings(it).toByteArray(Charsets.UTF_8) },
            speeds = session.speeds
                .takeIf { it.isNotEmpty() }
                ?.let { BundleCodec.encodeReadings(it).toByteArray(Charsets.UTF_8) },
        )

        fun SessionEntity.toArchive() = Archive.Session(
            id = id,
            startedAtUtc = startedAtUtc,
            endedAtUtc = endedAtUtc,
            tzOffsetMinutes = tzOffsetMinutes,
            exerciseType = exerciseType,
            activeDurationMs = activeDurationMs,
            totalDistanceM = totalDistanceM,
            source = source,
            hcWriteState = hcWriteState,
            hcError = hcError,
            matchedRouteId = matchedRouteId,
            matchCoverage = matchCoverage,
            committedAtM = committedAtM,
            avgHr = avgHr,
            ingestedAt = ingestedAt,
        )

        fun Archive.Session.toEntity() = SessionEntity(
            id = id,
            startedAtUtc = startedAtUtc,
            endedAtUtc = endedAtUtc,
            tzOffsetMinutes = tzOffsetMinutes,
            exerciseType = exerciseType,
            activeDurationMs = activeDurationMs,
            totalDistanceM = totalDistanceM,
            source = source,
            hcWriteState = hcWriteState,
            hcError = hcError,
            matchedRouteId = matchedRouteId,
            matchCoverage = matchCoverage,
            committedAtM = committedAtM,
            avgHr = avgHr,
            ingestedAt = ingestedAt,
        )

        fun RouteEntity.toArchive() = Archive.Route(
            id = id,
            name = name,
            nameIsCustom = nameIsCustom,
            exerciseType = exerciseType,
            lengthM = lengthM,
            memberCount = memberCount,
            medoidSessionId = medoidSessionId,
            createdAt = createdAt,
            active = active,
            priority = priority,
        )

        fun Archive.Route.toEntity() = RouteEntity(
            id = id,
            name = name,
            nameIsCustom = nameIsCustom,
            exerciseType = exerciseType,
            lengthM = lengthM,
            memberCount = memberCount,
            medoidSessionId = medoidSessionId,
            createdAt = createdAt,
            active = active,
            // Clamped on the way in, because an archive written before the scale
            // was rescaled (schema v7) carries 1..10. Nothing would crash on a 9
            // — it is an integer the matcher only ever compares — but the picker
            // has no button for it, so the route would show no priority at all
            // and the only way to change it would be to pick a different one
            // first. A 9 becomes the top of the new scale, which is what it
            // meant.
            priority = priority.coerceIn(Tuning.MIN_ROUTE_PRIORITY, Tuning.MAX_ROUTE_PRIORITY),
        )

        fun Sample.toEntity(sessionId: String) = SampleEntity(
            sessionId = sessionId,
            tOffsetMs = tOffsetMs,
            activeMs = activeMs,
            lat = lat,
            lon = lon,
            altM = altM,
            hrBpm = hrBpm,
            accuracyM = accuracyM,
            paused = paused,
        )
    }
}
