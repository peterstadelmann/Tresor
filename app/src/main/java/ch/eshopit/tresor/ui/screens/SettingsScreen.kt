package ch.eshopit.tresor.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import ch.eshopit.tresor.crypto.KeyManager
import ch.eshopit.tresor.ui.AppViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: AppViewModel,
    snackbar: SnackbarHostState,
    onEnableBiometric: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    var showChangePin by remember { mutableStateOf(false) }
    val bioAvailable = remember { vm.biometricAvailable() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Einstellungen") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            ListItem(
                leadingContent = { Icon(Icons.Filled.Fingerprint, null) },
                headlineContent = { Text("Mit Fingerabdruck entsperren") },
                supportingContent = {
                    Text(
                        if (bioAvailable || vm.biometricEnabled) "Zusätzlich zum Code"
                        else "Nicht verfügbar – richte zuerst einen Fingerabdruck in den Android-Einstellungen ein"
                    )
                },
                trailingContent = {
                    Switch(
                        checked = vm.biometricEnabled,
                        enabled = bioAvailable || vm.biometricEnabled,
                        onCheckedChange = { on -> if (on) onEnableBiometric() else vm.disableBiometric() }
                    )
                }
            )
            ListItem(
                leadingContent = { Icon(Icons.Filled.DeleteSweep, null) },
                headlineContent = { Text("Originale nach dem Verschlüsseln löschen") },
                supportingContent = {
                    Text("Entfernt die unverschlüsselte Datei, sofern der Speicherort das erlaubt (z. B. Downloads, Dateien-App).")
                },
                trailingContent = {
                    Switch(checked = vm.deleteOriginals, onCheckedChange = vm::updateDeleteOriginals)
                }
            )
            ListItem(
                leadingContent = { Icon(Icons.Filled.Password, null) },
                headlineContent = { Text("Code ändern") },
                modifier = Modifier.clickable { showChangePin = true }
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            ListItem(
                leadingContent = { Icon(Icons.Filled.Security, null) },
                headlineContent = { Text("Sicherheit") },
                supportingContent = {
                    Text(
                        "Dateien: AES-256-GCM. Schlüssel: geschützt durch deinen Code (PBKDF2) und den " +
                            "Android Keystore dieses Geräts. Die App sperrt sich automatisch, sobald sie in den " +
                            "Hintergrund geht; Screenshots sind blockiert.\n\nWichtig: Ohne Code gibt es keine " +
                            "Wiederherstellung. Beim Deinstallieren der App gehen alle Tresor-Daten verloren – " +
                            "vorher wichtige Dateien entschlüsseln und sichern."
                    )
                }
            )
            ListItem(
                leadingContent = { Icon(Icons.Filled.Info, null) },
                headlineContent = { Text("Tresor 1.0") },
                supportingContent = { Text("eShop IT Solutions GmbH") }
            )
        }
    }

    if (showChangePin) {
        ChangePinDialog(
            onDismiss = { showChangePin = false },
            onSubmit = { current, new, setError ->
                vm.changePin(current, new) { ok, msg ->
                    if (ok) {
                        showChangePin = false
                        vm.showMessage(msg)
                    } else {
                        setError(msg)
                    }
                }
            }
        )
    }
}

@Composable
private fun ChangePinDialog(
    onDismiss: () -> Unit,
    onSubmit: (current: String, new: String, setError: (String) -> Unit) -> Unit
) {
    var current by remember { mutableStateOf("") }
    var new1 by remember { mutableStateOf("") }
    var new2 by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val digitsOnly: (String) -> String = { s -> s.filter(Char::isDigit).take(KeyManager.MAX_PIN_LENGTH) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Code ändern") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PinField("Aktueller Code", current) { current = digitsOnly(it); error = null }
                PinField("Neuer Code", new1) { new1 = digitsOnly(it); error = null }
                PinField("Neuen Code bestätigen", new2) { new2 = digitsOnly(it); error = null }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && current.isNotEmpty() && new1.isNotEmpty(),
                onClick = {
                    when {
                        new1.length < KeyManager.MIN_PIN_LENGTH ->
                            error = "Der neue Code braucht mindestens ${KeyManager.MIN_PIN_LENGTH} Ziffern"
                        new1 != new2 -> error = "Die neuen Codes stimmen nicht überein"
                        else -> {
                            busy = true
                            onSubmit(current, new1) { msg -> busy = false; error = msg }
                        }
                    }
                }
            ) { Text(if (busy) "Bitte warten …" else "Ändern") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Abbrechen") } }
    )
}

@Composable
private fun PinField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth()
    )
}
