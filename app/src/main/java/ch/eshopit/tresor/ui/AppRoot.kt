package ch.eshopit.tresor.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.eshopit.tresor.ui.screens.AppLockScreen
import ch.eshopit.tresor.ui.screens.LockScreen
import ch.eshopit.tresor.ui.screens.SettingsScreen
import ch.eshopit.tresor.ui.screens.SetupScreen
import ch.eshopit.tresor.ui.screens.VaultScreen
import ch.eshopit.tresor.ui.screens.ViewerScreen
import ch.eshopit.tresor.vault.VaultItem
import ch.eshopit.tresor.vault.VaultSession
import kotlinx.coroutines.launch

/** Aktionen, die eine fremde Activity starten (Dateiauswahl, Speichern, Öffnen mit). */
class VaultActions(
    val import: (Array<String>) -> Unit,
    val export: (VaultItem) -> Unit,
    val openWith: (VaultItem) -> Unit,
    val importFolder: () -> Unit,
    /** Tresor-Ordner in einen frei gewählten Zielordner entschlüsseln (optional danach entfernen). */
    val exportFolder: (path: String, removeAfter: Boolean) -> Unit,
    /** Android-Berechtigung "Zugriff auf alle Dateien" anfragen. */
    val requestStorageAccess: () -> Unit
)

@Composable
fun AppRoot(
    vm: AppViewModel,
    onBiometricUnlock: () -> Unit,
    onEnableBiometric: () -> Unit
) {
    val unlocked by vm.unlocked.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(state.message) {
        state.message?.let {
            vm.consumeMessage()
            snackbar.showSnackbar(it)
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        VaultSession.externalActionPending = false
        if (uris.isNotEmpty()) vm.importUris(uris)
    }
    val folderImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        VaultSession.externalActionPending = false
        if (uri != null) vm.importFolder(uri)
    }
    val folderExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        VaultSession.externalActionPending = false
        vm.finishFolderExport(uri)
    }
    val storagePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        VaultSession.externalActionPending = false
    }
    val exportLauncher = rememberLauncherForActivityResult(CreateDocumentContract()) { uri ->
        VaultSession.externalActionPending = false
        vm.finishExport(uri)
    }

    val actions = remember(vm) {
        VaultActions(
            import = { types ->
                VaultSession.externalActionPending = true
                importLauncher.launch(types)
            },
            export = { item ->
                vm.pendingExportId = item.id
                VaultSession.externalActionPending = true
                exportLauncher.launch(item.name to item.mime)
            },
            importFolder = {
                VaultSession.externalActionPending = true
                folderImportLauncher.launch(null)
            },
            exportFolder = { path, removeAfter ->
                vm.pendingFolderExport = AppViewModel.FolderExport(path, removeAfter)
                VaultSession.externalActionPending = true
                folderExportLauncher.launch(null)
            },
            requestStorageAccess = {
                VaultSession.externalActionPending = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val appSpecific = Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                    try {
                        context.startActivity(appSpecific)
                    } catch (e: ActivityNotFoundException) {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                } else {
                    storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            },
            openWith = { item ->
                scope.launch {
                    val uri = vm.prepareOpenWith(item) ?: return@launch
                    launchViewer(context, uri, item.mime) { vm.showMessage(it) }
                }
            }
        )
    }

    when {
        !vm.isSetUp -> SetupScreen(
            busy = state.pinBusy,
            snackbar = snackbar,
            onSubmit = vm::setupPin
        )

        !unlocked -> LockScreen(
            state = state,
            biometricEnabled = vm.biometricEnabled,
            snackbar = snackbar,
            lockoutRemaining = vm.keyManager::lockoutRemainingMs,
            onSubmit = vm::unlockWithPin,
            onBiometric = onBiometricUnlock
        )

        state.showAppLock -> AppLockScreen(
            snackbar = snackbar,
            onBack = vm::closeAppLock
        )

        state.showSettings -> SettingsScreen(
            vm = vm,
            snackbar = snackbar,
            onEnableBiometric = onEnableBiometric,
            onBack = vm::closeSettings
        )

        state.viewer != null -> ViewerScreen(
            vm = vm,
            item = state.viewer!!,
            busy = state.busy,
            snackbar = snackbar,
            actions = actions,
            onBack = vm::closeViewer
        )

        else -> VaultScreen(
            vm = vm,
            state = state,
            snackbar = snackbar,
            actions = actions
        )
    }

    if (unlocked && state.askBiometric) {
        AlertDialog(
            onDismissRequest = vm::dismissBiometricAsk,
            title = { Text("Fingerabdruck verwenden?") },
            text = { Text("Den Tresor künftig auch mit Fingerabdruck entsperren. Der Code bleibt weiterhin gültig.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.dismissBiometricAsk()
                    onEnableBiometric()
                }) { Text("Aktivieren") }
            },
            dismissButton = { TextButton(onClick = vm::dismissBiometricAsk) { Text("Später") } }
        )
    }
}

private fun launchViewer(context: Context, uri: Uri, mime: String, onError: (String) -> Unit) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    intent.clipData = ClipData.newRawUri("", uri)
    VaultSession.externalActionPending = true
    try {
        context.startActivity(Intent.createChooser(intent, "Öffnen mit …"))
    } catch (e: ActivityNotFoundException) {
        VaultSession.externalActionPending = false
        onError("Keine passende App gefunden")
    }
}

/** Speichern-Dialog mit vorgeschlagenem Dateinamen und passendem Dateityp. */
class CreateDocumentContract : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.second)
            .putExtra(Intent.EXTRA_TITLE, input.first)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}
