package de.hecate.wear

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import de.hecate.wear.data.BundleStore
import de.hecate.wear.data.SessionStore
import de.hecate.wear.recorder.RecorderBus
import de.hecate.wear.transfer.Uploader

/**
 * §4: no dependency injection framework. Fifteen objects wired by hand in one
 * place is less machinery than a DI graph, and the wiring is readable.
 *
 * Lint sees a singleton holding objects that hold a Context and calls it a leak.
 * What [init] stores is `context.applicationContext`, whose lifetime is the
 * process — there is nothing shorter-lived here to leak.
 */
@SuppressLint("StaticFieldLeak")
object AppGraph {
    lateinit var sessions: SessionStore
        private set
    lateinit var bundles: BundleStore
        private set
    lateinit var uploader: Uploader
        private set

    val recorder = RecorderBus()

    @Volatile
    private var initialised = false

    fun init(context: Context) {
        if (initialised) return
        synchronized(this) {
            if (initialised) return
            val app = context.applicationContext
            sessions = SessionStore(app)
            bundles = BundleStore(app)
            uploader = Uploader(app, sessions)
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
