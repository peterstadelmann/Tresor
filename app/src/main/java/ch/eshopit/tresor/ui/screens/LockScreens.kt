package ch.eshopit.tresor.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import ch.eshopit.tresor.crypto.KeyManager
import ch.eshopit.tresor.ui.UiState
import kotlinx.coroutines.delay

@Composable
fun SetupScreen(
    busy: Boolean,
    snackbar: SnackbarHostState,
    onSubmit: (String) -> Unit
) {
    var first by rememberSaveable { mutableStateOf<String?>(null) }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    PinScaffold(snackbar) {
        Icon(Icons.Filled.Shield, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(
            if (first == null) "Code festlegen" else "Code bestätigen",
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (first == null)
                "Wähle einen Code mit ${KeyManager.MIN_PIN_LENGTH}–${KeyManager.MAX_PIN_LENGTH} Ziffern. " +
                    "Ohne diesen Code lassen sich deine Dateien nicht wiederherstellen."
            else "Gib den Code zur Bestätigung erneut ein.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        PinPad(pin = pin, onPinChange = { pin = it; error = null }, enabled = !busy)
        ErrorLine(error)
        Spacer(Modifier.height(8.dp))
        if (busy) {
            CircularProgressIndicator()
        } else {
            Button(
                onClick = {
                    val f = first
                    when {
                        f == null -> { first = pin; pin = "" }
                        f == pin -> onSubmit(pin)
                        else -> { error = "Die Codes stimmen nicht überein. Bitte neu beginnen."; first = null; pin = "" }
                    }
                },
                enabled = pin.length >= KeyManager.MIN_PIN_LENGTH,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (first == null) "Weiter" else "Tresor erstellen") }
        }
    }
}

@Composable
fun LockScreen(
    state: UiState,
    biometricEnabled: Boolean,
    snackbar: SnackbarHostState,
    lockoutRemaining: () -> Long,
    onSubmit: (String) -> Unit,
    onBiometric: () -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var remaining by remember { mutableLongStateOf(lockoutRemaining()) }
    var autoPrompted by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            remaining = lockoutRemaining()
            delay(500)
        }
    }

    // Fingerabdruck-Dialog automatisch einmal anzeigen, sobald die App sichtbar ist.
    LifecycleResumeEffect(biometricEnabled) {
        if (biometricEnabled && !autoPrompted) {
            autoPrompted = true
            onBiometric()
        }
        onPauseOrDispose { }
    }

    val locked = remaining > 0
    val fingerprintKey: (@Composable () -> Unit)? =
        if (biometricEnabled) {
            @Composable {
                PadIconKey(Icons.Filled.Fingerprint, "Fingerabdruck", enabled = !state.pinBusy, onClick = onBiometric)
            }
        } else null

    PinScaffold(snackbar) {
        Icon(Icons.Filled.Lock, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("Tresor gesperrt", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            if (biometricEnabled) "Code eingeben oder Fingerabdruck verwenden" else "Code eingeben",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        PinPad(
            pin = pin,
            onPinChange = { pin = it },
            enabled = !state.pinBusy && !locked,
            leftKey = fingerprintKey
        )
        ErrorLine(if (locked) "Zu viele Fehlversuche – noch ${(remaining + 999) / 1000} s warten." else state.pinError)
        Spacer(Modifier.height(8.dp))
        if (state.pinBusy) {
            CircularProgressIndicator()
        } else {
            Button(
                onClick = { onSubmit(pin); pin = "" },
                enabled = pin.length >= KeyManager.MIN_PIN_LENGTH && !locked,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Entsperren") }
        }
    }
}

@Composable
private fun PinScaffold(snackbar: SnackbarHostState, content: @Composable () -> Unit) {
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                Modifier
                    .widthIn(max = 360.dp)
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) { content() }
        }
    }
}

@Composable
private fun ErrorLine(text: String?) {
    Box(Modifier.height(40.dp), contentAlignment = Alignment.Center) {
        if (text != null) {
            Text(
                text,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun PinPad(
    pin: String,
    onPinChange: (String) -> Unit,
    enabled: Boolean,
    leftKey: (@Composable () -> Unit)? = null
) {
    // Punkte-Anzeige
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(maxOf(KeyManager.MIN_PIN_LENGTH, pin.length)) { i ->
            val filled = i < pin.length
            Box(
                Modifier
                    .size(14.dp)
                    .background(
                        if (filled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                        CircleShape
                    )
            )
        }
    }
    Spacer(Modifier.height(24.dp))

    val add: (String) -> Unit = { d -> if (pin.length < KeyManager.MAX_PIN_LENGTH) onPinChange(pin + d) }
    val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"))
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                row.forEach { d -> PadDigit(d, enabled) { add(d) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) { leftKey?.invoke() }
            PadDigit("0", enabled) { add("0") }
            PadIconKey(
                Icons.AutoMirrored.Filled.Backspace,
                "Löschen",
                enabled = enabled && pin.isNotEmpty()
            ) { onPinChange(pin.dropLast(1)) }
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun PadDigit(digit: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.size(72.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(digit, fontSize = 28.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PadIconKey(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(72.dp)) {
        Icon(icon, description, Modifier.size(32.dp))
    }
}
