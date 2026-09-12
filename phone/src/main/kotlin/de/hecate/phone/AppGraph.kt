package de.hecate.phone

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import de.hecate.phone.archive.ArchiveRepository
import de.hecate.phone.db.HecateDatabase
import de.hecate.phone.health.HealthConnectWriter
import de.hecate.phone.ingest.DataLayerIngest
import de.hecate.phone.ingest.SessionRepository
import de.hecate.phone.tcx.TcxImporter

/**
 * §4: no dependency injection framework. Manual constructor wiring, one object;
 * Hilt would add build complexity for about fifteen objects.
 *
 * Lint sees a singleton holding objects that hold a Context and calls it a leak.
 * What [init] stores is `context.applicationContext`, whose lifetime is the
 * process — there is nothing shorter-lived here to leak.
 */
@SuppressLint("StaticFieldLeak")
object AppGraph {
    lateinit var db: HecateDatabase
        private set
    lateinit var repository: SessionRepository
        private set
    lateinit var healthConnect: HealthConnectWriter
        private set
    lateinit var importer: TcxImporter
        private set
    lateinit var archive: ArchiveRepository
        private set
    lateinit var dataLayer: DataLayerIngest
        private set

    @Volatile
    private var initialised = false

    fun init(context: Context) {
        if (initialised) return
        synchronized(this) {
            if (initialised) return
            val app = context.applicationContext
            db = HecateDatabase.get(app)
            healthConnect = HealthConnectWriter(app)
            repository = SessionRepository(app, db)
            importer = TcxImporter(app, repository)
            archive = ArchiveRepository(app, db)
            dataLayer = DataLayerIngest(app, repository)
            initialised = true
        }
    }
}

class HecateApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
    }
}
