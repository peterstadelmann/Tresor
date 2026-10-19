package ch.eshopit.tresor

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import ch.eshopit.tresor.crypto.KeyManager
import ch.eshopit.tresor.vault.VaultRepository
import ch.eshopit.tresor.vault.VaultSession

class TresorApp : Application() {

    lateinit var keyManager: KeyManager
        private set
    lateinit var repository: VaultRepository
        private set

    private var stoppedAt = 0L

    override fun onCreate() {
        super.onCreate()
        keyManager = KeyManager(this)
        repository = VaultRepository(this)
        repository.clearShared()

        // Automatisch sperren, sobald die App in den Hintergrund geht.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                stoppedAt = SystemClock.elapsedRealtime()
                if (!VaultSession.externalActionPending) lockNow()
            }

            override fun onStart(owner: LifecycleOwner) {
                // Nach einer Dateiauswahl o. Ä. nur entsperrt bleiben, wenn es schnell ging.
                if (VaultSession.externalActionPending &&
                    SystemClock.elapsedRealtime() - stoppedAt > EXTERNAL_GRACE_MS
                ) lockNow()
                VaultSession.externalActionPending = false
            }
        })
    }

    fun lockNow() {
        VaultSession.lock()
        repository.clearShared()
    }

    companion object {
        private const val EXTERNAL_GRACE_MS = 3 * 60 * 1000L
    }
}
