package ch.eshopit.tresor.vault

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class VaultLockedException : IllegalStateException("Tresor ist gesperrt")

/** Hält den Master-Key nur im Arbeitsspeicher, solange der Tresor entsperrt ist. */
object VaultSession {
    @Volatile
    private var key: ByteArray? = null

    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    /**
     * Wird gesetzt, bevor die App selbst eine fremde Activity startet (Dateiauswahl,
     * Speichern-Dialog, "Öffnen mit"). Dann wird beim Wechsel in den Hintergrund nicht
     * sofort gesperrt.
     */
    @Volatile
    var externalActionPending = false

    fun unlock(newKey: ByteArray) {
        key?.fill(0)
        key = newKey
        _unlocked.value = true
    }

    /** Liefert eine Kopie des Schlüssels; der Aufrufer überschreibt sie nach Gebrauch. */
    fun keyCopy(): ByteArray = key?.copyOf() ?: throw VaultLockedException()

    fun lock() {
        key?.fill(0)
        key = null
        _unlocked.value = false
    }
}
