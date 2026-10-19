package ch.eshopit.tresor.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import ch.eshopit.tresor.ui.theme.TresorTheme

class MainActivity : FragmentActivity() {

    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keine Screenshots, keine Vorschau in der App-Übersicht
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent {
            TresorTheme {
                AppRoot(
                    vm = vm,
                    onBiometricUnlock = ::biometricUnlock,
                    onEnableBiometric = ::enableBiometric
                )
            }
        }
    }

    private fun biometricUnlock() {
        val cipher = vm.biometricUnlockCipher() ?: return
        BiometricHelper.authenticate(
            activity = this,
            cipher = cipher,
            title = "Tresor entsperren",
            negativeButton = "Code verwenden",
            onSuccess = vm::finishBiometricUnlock,
            onError = vm::showMessage
        )
    }

    private fun enableBiometric() {
        val cipher = vm.biometricEnableCipher() ?: return
        BiometricHelper.authenticate(
            activity = this,
            cipher = cipher,
            title = "Fingerabdruck aktivieren",
            negativeButton = "Abbrechen",
            onSuccess = vm::finishBiometricEnable,
            onError = vm::showMessage
        )
    }
}
