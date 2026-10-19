package ch.eshopit.tresor.applock

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.view.WindowManager
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import ch.eshopit.tresor.TresorApp
import ch.eshopit.tresor.ui.BiometricHelper
import ch.eshopit.tresor.ui.screens.AppLockGate
import ch.eshopit.tresor.ui.theme.TresorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Sperrbildschirm, der über einer gesperrten App erscheint. */
class AppLockActivity : FragmentActivity() {

    private val keyManager get() = (application as TresorApp).keyManager

    private var target by mutableStateOf("")
    private var busy by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var biometricEnabled by mutableStateOf(false)
    private var autoPrompted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        val pkg = intent.getStringExtra(EXTRA_PACKAGE)
        if (pkg == null) {
            finish()
            return
        }
        target = pkg
        biometricEnabled = keyManager.isBiometricEnabled
        onBackPressedDispatcher.addCallback(this) { goHome() }

        setContent {
            TresorTheme {
                val info = remember(target) { loadAppInfo(target) }
                AppLockGate(
                    label = info.first,
                    icon = info.second,
                    busy = busy,
                    error = error,
                    biometricEnabled = biometricEnabled,
                    lockoutRemaining = keyManager::lockoutRemainingMs,
                    onSubmit = ::checkPin,
                    onBiometric = ::biometric,
                    onCancel = ::goHome
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_PACKAGE)?.let {
            target = it
            error = null
            autoPrompted = false
        }
    }

    override fun onResume() {
        super.onResume()
        biometricEnabled = keyManager.isBiometricEnabled
        if (biometricEnabled && !autoPrompted && keyManager.lockoutRemainingMs() == 0L) {
            autoPrompted = true
            biometric()
        }
    }

    private fun checkPin(pin: String) {
        val wait = keyManager.lockoutRemainingMs()
        if (wait > 0) {
            error = "Zu viele Fehlversuche – bitte ${(wait + 999) / 1000} s warten."
            return
        }
        busy = true
        error = null
        lifecycleScope.launch {
            val chars = pin.toCharArray()
            val result = withContext(Dispatchers.Default) { runCatching { keyManager.unwrapWithPin(chars) } }
            chars.fill('\u0000')
            busy = false
            val key = result.getOrNull()
            when {
                result.isFailure -> error = "Fehler: ${result.exceptionOrNull()?.message}"
                key != null -> {
                    key.fill(0)
                    keyManager.resetFailures()
                    unlocked()
                }
                else -> {
                    val lockout = keyManager.registerFailure()
                    error = if (lockout > 0) "Falscher Code. Gesperrt für ${lockout / 1000} s." else "Falscher Code."
                }
            }
        }
    }

    private fun biometric() {
        val cipher = try {
            keyManager.biometricDecryptCipher()
        } catch (e: KeyPermanentlyInvalidatedException) {
            keyManager.disableBiometric()
            biometricEnabled = false
            error = "Fingerabdrücke wurden geändert – bitte Code verwenden."
            return
        } catch (e: Exception) {
            error = "Fingerabdruck nicht verfügbar – bitte Code verwenden."
            return
        }
        BiometricHelper.authenticate(
            activity = this,
            cipher = cipher,
            title = "App entsperren",
            negativeButton = "Code verwenden",
            onSuccess = { c ->
                runCatching { keyManager.finishBiometricUnlock(c) }
                    .onSuccess { key ->
                        key.fill(0)
                        keyManager.resetFailures()
                        unlocked()
                    }
                    .onFailure { error = "Bitte Code verwenden." }
            },
            onError = { error = it }
        )
    }

    private fun unlocked() {
        AppLockStore.markUnlocked(target)
        finish()
    }

    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }

    private fun loadAppInfo(pkg: String): Pair<String, ImageBitmap?> = try {
        @Suppress("DEPRECATION")
        val ai = packageManager.getApplicationInfo(pkg, 0)
        packageManager.getApplicationLabel(ai).toString() to
            packageManager.getApplicationIcon(ai).toBitmap(144, 144).asImageBitmap()
    } catch (e: Exception) {
        pkg to null
    }

    companion object {
        private const val EXTRA_PACKAGE = "package"

        fun intent(context: Context, pkg: String): Intent =
            Intent(context, AppLockActivity::class.java)
                .putExtra(EXTRA_PACKAGE, pkg)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
    }
}
