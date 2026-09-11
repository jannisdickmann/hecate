package de.hecate.phone

import android.app.Application
import android.content.Context
import de.hecate.phone.db.HecateDatabase
import de.hecate.phone.health.HealthConnectWriter
import de.hecate.phone.ingest.SessionRepository
import de.hecate.phone.tcx.TcxImporter

/**
 * §4: manual constructor wiring, one object. Hilt would add build complexity for
 * about fifteen objects.
 */
object AppGraph {
    lateinit var db: HecateDatabase
        private set
    lateinit var repository: SessionRepository
        private set
    lateinit var healthConnect: HealthConnectWriter
        private set
    lateinit var importer: TcxImporter
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
