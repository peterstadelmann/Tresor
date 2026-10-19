package ch.eshopit.tresor.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.LruCache
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.eshopit.tresor.TresorApp
import ch.eshopit.tresor.vault.VaultItem
import ch.eshopit.tresor.vault.VaultLockedException
import ch.eshopit.tresor.vault.VaultSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.crypto.Cipher

data class UiState(
    val items: List<VaultItem> = emptyList(),
    val busy: String? = null,
    val message: String? = null,
    val pinBusy: Boolean = false,
    val pinError: String? = null,
    val viewer: VaultItem? = null,
    val showSettings: Boolean = false,
    val showAppLock: Boolean = false,
    val askBiometric: Boolean = false
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val tresor = app as TresorApp
    val keyManager = tresor.keyManager
    private val repo = tresor.repository
    private val settings = app.getSharedPreferences("tresor_settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    val unlocked: StateFlow<Boolean> = VaultSession.unlocked

    var isSetUp by mutableStateOf(keyManager.isSetUp)
        private set
    var biometricEnabled by mutableStateOf(keyManager.isBiometricEnabled)
        private set
    var deleteOriginals by mutableStateOf(settings.getBoolean(KEY_DELETE_ORIGINALS, false))
        private set

    /** Element, das gerade über den Speichern-Dialog exportiert wird. */
    var pendingExportId: String? = null

    private val thumbCache = LruCache<String, ImageBitmap>(300)

    init {
        viewModelScope.launch {
            VaultSession.unlocked.collect { isUnlocked ->
                if (isUnlocked) {
                    refresh()
                } else {
                    thumbCache.evictAll()
                    _state.update { UiState(message = it.message) }
                }
            }
        }
    }

    // ---------------------------------------------------------------- Einrichtung & Entsperren

    fun biometricAvailable(): Boolean =
        BiometricManager.from(getApplication()).canAuthenticate(BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS

    fun setupPin(pin: String) {
        _state.update { it.copy(pinBusy = true) }
        viewModelScope.launch {
            val chars = pin.toCharArray()
            val result = withContext(Dispatchers.Default) { runCatching { keyManager.setup(chars) } }
            chars.fill('\u0000')
            _state.update { it.copy(pinBusy = false) }
            result
                .onSuccess { key ->
                    isSetUp = true
                    VaultSession.unlock(key)
                    if (biometricAvailable()) _state.update { it.copy(askBiometric = true) }
                }
                .onFailure { showMessage("Einrichtung fehlgeschlagen: ${it.message}") }
        }
    }

    fun unlockWithPin(pin: String) {
        val wait = keyManager.lockoutRemainingMs()
        if (wait > 0) {
            _state.update { it.copy(pinError = "Zu viele Fehlversuche – bitte ${(wait + 999) / 1000} s warten.") }
            return
        }
        _state.update { it.copy(pinBusy = true, pinError = null) }
        viewModelScope.launch {
            val chars = pin.toCharArray()
            val result = withContext(Dispatchers.Default) { runCatching { keyManager.unwrapWithPin(chars) } }
            chars.fill('\u0000')
            result
                .onSuccess { key ->
                    if (key != null) {
                        keyManager.resetFailures()
                        _state.update { it.copy(pinBusy = false, pinError = null) }
                        VaultSession.unlock(key)
                    } else {
                        val lockout = keyManager.registerFailure()
                        val msg = if (lockout > 0) {
                            "Falscher Code. Gesperrt für ${lockout / 1000} s."
                        } else {
                            "Falscher Code. Noch ${keyManager.attemptsBeforeLockout()} Versuch(e) bis zur Wartezeit."
                        }
                        _state.update { it.copy(pinBusy = false, pinError = msg) }
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(pinBusy = false, pinError = "Fehler: ${e.message}") }
                }
        }
    }

    fun biometricUnlockCipher(): Cipher? = try {
        keyManager.biometricDecryptCipher()
    } catch (e: KeyPermanentlyInvalidatedException) {
        keyManager.disableBiometric()
        biometricEnabled = false
        showMessage("Fingerabdrücke wurden geändert. Bitte mit Code entsperren und Biometrie neu aktivieren.")
        null
    } catch (e: Exception) {
        showMessage("Biometrie nicht verfügbar: ${e.message}")
        null
    }

    fun finishBiometricUnlock(cipher: Cipher) {
        runCatching { keyManager.finishBiometricUnlock(cipher) }
            .onSuccess { key ->
                keyManager.resetFailures()
                VaultSession.unlock(key)
            }
            .onFailure { showMessage("Entsperren fehlgeschlagen. Bitte Code verwenden.") }
    }

    fun biometricEnableCipher(): Cipher? =
        runCatching { keyManager.biometricEncryptCipher() }
            .onFailure { showMessage("Biometrie konnte nicht eingerichtet werden: ${it.message}") }
            .getOrNull()

    fun finishBiometricEnable(cipher: Cipher) {
        runCatching { withKey { keyManager.finishBiometricEnable(cipher, it) } }
            .onSuccess {
                biometricEnabled = true
                showMessage("Fingerabdruck-Entsperrung aktiviert")
            }
            .onFailure {
                keyManager.disableBiometric()
                showMessage("Aktivierung fehlgeschlagen: ${it.message}")
            }
    }

    fun disableBiometric() {
        keyManager.disableBiometric()
        biometricEnabled = false
    }

    fun dismissBiometricAsk() = _state.update { it.copy(askBiometric = false) }

    fun changePin(current: String, new: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val cur = current.toCharArray()
            val nxt = new.toCharArray()
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val master = keyManager.unwrapWithPin(cur) ?: return@runCatching false
                    try {
                        keyManager.changePin(nxt, master)
                    } finally {
                        master.fill(0)
                    }
                    true
                }
            }
            cur.fill('\u0000'); nxt.fill('\u0000')
            when {
                result.isFailure -> onResult(false, "Fehler: ${result.exceptionOrNull()?.message}")
                result.getOrNull() == true -> onResult(true, "Code geändert")
                else -> onResult(false, "Der aktuelle Code ist falsch")
            }
        }
    }

    // ---------------------------------------------------------------- Tresor-Inhalt

    private suspend fun refresh() {
        if (!VaultSession.unlocked.value) return
        val items = withContext(Dispatchers.IO) {
            runCatching { withKey { repo.list(it) } }.getOrDefault(emptyList())
        }
        _state.update { it.copy(items = items) }
    }

    fun importUris(uris: List<Uri>) {
        viewModelScope.launch {
            val key = try {
                VaultSession.keyCopy()
            } catch (e: VaultLockedException) {
                showMessage("Tresor ist gesperrt – Import abgebrochen")
                return@launch
            }
            var ok = 0
            var failed = 0
            var originalsKept = 0
            try {
                uris.forEachIndexed { index, uri ->
                    _state.update { it.copy(busy = "Verschlüssele ${index + 1} von ${uris.size} …") }
                    val result = withContext(Dispatchers.IO) { runCatching { repo.import(key, uri) } }
                    if (result.isSuccess) {
                        ok++
                        if (deleteOriginals && !withContext(Dispatchers.IO) { repo.deleteOriginal(uri) }) {
                            originalsKept++
                        }
                    } else {
                        failed++
                    }
                }
            } finally {
                key.fill(0)
                _state.update { it.copy(busy = null) }
            }
            refresh()
            showMessage(buildString {
                append("$ok Element(e) verschlüsselt")
                if (failed > 0) append(", $failed fehlgeschlagen")
                if (originalsKept > 0) append(". $originalsKept Original(e) konnten nicht gelöscht werden")
            })
        }
    }

    fun finishExport(dest: Uri?) {
        val id = pendingExportId
        pendingExportId = null
        if (dest == null || id == null) return
        val item = _state.value.items.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = "Entschlüssele „${item.name}“ …") }
            val result = withContext(Dispatchers.IO) { runCatching { withKey { repo.export(it, item, dest) } } }
            _state.update { it.copy(busy = null) }
            showMessage(
                if (result.isSuccess) "Entschlüsselt gespeichert: ${item.name}"
                else "Export fehlgeschlagen: ${result.exceptionOrNull()?.message}"
            )
        }
    }

    suspend fun prepareOpenWith(item: VaultItem): Uri? {
        _state.update { it.copy(busy = "Entschlüssele „${item.name}“ …") }
        val result = withContext(Dispatchers.IO) { runCatching { withKey { repo.prepareShare(it, item) } } }
        _state.update { it.copy(busy = null) }
        return result.onFailure { showMessage("Öffnen fehlgeschlagen: ${it.message}") }.getOrNull()
    }

    fun delete(item: VaultItem) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.delete(item) }
            thumbCache.remove(item.id)
            _state.update { s ->
                s.copy(
                    items = s.items.filterNot { it.id == item.id },
                    viewer = if (s.viewer?.id == item.id) null else s.viewer
                )
            }
            showMessage("„${item.name}“ gelöscht")
        }
    }

    suspend fun thumbnail(item: VaultItem): ImageBitmap? {
        thumbCache.get(item.id)?.let { return it }
        val bmp = withContext(Dispatchers.IO) {
            runCatching { withKey { repo.thumbnail(it, item) } }.getOrNull()?.asImageBitmap()
        }
        if (bmp != null) thumbCache.put(item.id, bmp)
        return bmp
    }

    suspend fun loadFullImage(item: VaultItem): ImageBitmap? =
        withContext(Dispatchers.IO) {
            runCatching { withKey { repo.loadImage(it, item, 2560) } }
                .onFailure { showMessage("Bild konnte nicht geladen werden: ${it.message}") }
                .getOrNull()
                ?.asImageBitmap()
        }

    // ---------------------------------------------------------------- Navigation & Einstellungen

    fun openViewer(item: VaultItem) = _state.update { it.copy(viewer = item) }
    fun closeViewer() = _state.update { it.copy(viewer = null) }
    fun openSettings() = _state.update { it.copy(showSettings = true) }
    fun closeSettings() = _state.update { it.copy(showSettings = false) }
    fun openAppLock() = _state.update { it.copy(showAppLock = true) }
    fun closeAppLock() = _state.update { it.copy(showAppLock = false) }

    fun updateDeleteOriginals(value: Boolean) {
        deleteOriginals = value
        settings.edit().putBoolean(KEY_DELETE_ORIGINALS, value).apply()
    }

    fun lock() = tresor.lockNow()

    fun showMessage(msg: String) = _state.update { it.copy(message = msg) }
    fun consumeMessage() = _state.update { it.copy(message = null) }

    private inline fun <T> withKey(block: (ByteArray) -> T): T {
        val key = VaultSession.keyCopy()
        try {
            return block(key)
        } finally {
            key.fill(0)
        }
    }

    companion object {
        private const val KEY_DELETE_ORIGINALS = "delete_originals"
    }
}
