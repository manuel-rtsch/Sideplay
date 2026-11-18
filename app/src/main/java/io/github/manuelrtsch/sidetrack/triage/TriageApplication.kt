package io.github.manuelrtsch.sidetrack.triage

import android.app.Application

/**
 * Triage-only entry point. It runs before the activity so a crash during startup still leaves a
 * log behind, and it records the platform and keystore state once per process.
 */
class TriageApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        TriageLog.init(this)
        TriageProbe.logPlatform()
        TriageProbe.probeKeystore()
        TriageProbe.probeSealedFiles(this)
        TriageLog.i("app", "application created")
    }
}
