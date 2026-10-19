package ch.eshopit.tresor.ui.screens

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import ch.eshopit.tresor.applock.AppLockStore
import ch.eshopit.tresor.applock.InstalledApp
import ch.eshopit.tresor.applock.isAppLockServiceEnabled
import ch.eshopit.tresor.applock.loadLaunchableApps
import ch.eshopit.tresor.crypto.KeyManager
import ch.eshopit.tresor.vault.VaultSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// ------------------------------------------------------------------ Auswahl der Apps

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppLockScreen(snackbar: SnackbarHostState, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current

    var serviceOn by remember { mutableStateOf(isAppLockServiceEnabled(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) serviceOn = isAppLockServiceEnabled(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var locked by remember { mutableStateOf(AppLockStore.lockedApps(context)) }
    var query by rememberSaveable { mutableStateOf("") }
    val apps by produceState<List<InstalledApp>?>(null) {
        value = withContext(Dispatchers.IO) { loadLaunchableApps(context) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("App-Sperre") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Zurück") }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        LazyColumn(
            Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item { ServiceCard(serviceOn, lockedCount = locked.size) { openAccessibilitySettings(context) } }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("App suchen") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            val list = apps
            if (list == null) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else {
                val shown = if (query.isBlank()) list
                else list.filter { it.label.contains(query.trim(), ignoreCase = true) }
                items(shown, key = { it.pkg }) { app ->
                    val isLocked = app.pkg in locked
                    ListItem(
                        leadingContent = { Image(app.icon, null, Modifier.size(40.dp)) },
                        headlineContent = { Text(app.label) },
                        supportingContent = if (isLocked) {
                            { Text("Gesperrt", color = MaterialTheme.colorScheme.primary) }
                        } else null,
                        trailingContent = {
                            Switch(
                                checked = isLocked,
                                onCheckedChange = { on ->
                                    AppLockStore.setLocked(context, app.pkg, on)
                                    locked = AppLockStore.lockedApps(context)
                                }
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ServiceCard(enabled: Boolean, lockedCount: Int, onOpenSettings: () -> Unit) {
    val colors = if (enabled) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    } else {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    }
    Card(colors = colors, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (enabled) {
                Icon(Icons.Filled.CheckCircle, null)
                Text("App-Sperre ist aktiv", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (lockedCount == 0) "Wähle unten die Apps, die mit Code oder Fingerabdruck geschützt werden sollen."
                    else "$lockedCount App(s) gesperrt. Eine entsperrte App bleibt offen, bis du sie verlässt oder der Bildschirm ausgeht.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "Tipp: Sperre auch „Einstellungen“, damit niemand die App-Sperre unbemerkt abschalten kann.",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Icon(Icons.Filled.Warning, null)
                Text("Dienst noch nicht aktiviert", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Damit Tresor andere Apps sperren kann, aktiviere unter Bedienungshilfen den Dienst " +
                        "„Tresor App-Sperre“. Tresor erfährt dabei nur, welche App gerade geöffnet wird – " +
                        "keine Inhalte.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "Ist der Schalter ausgegraut (Android 13+): Einstellungen → Apps → Tresor → ⋮ → " +
                        "„Eingeschränkte Einstellungen zulassen“, dann erneut versuchen.",
                    style = MaterialTheme.typography.bodySmall
                )
                Button(onClick = onOpenSettings) { Text("Bedienungshilfen öffnen") }
            }
        }
    }
}

private fun openAccessibilitySettings(context: Context) {
    VaultSession.externalActionPending = true
    context.startActivity(
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

// ------------------------------------------------------------------ Sperrbildschirm über fremden Apps

@Composable
fun AppLockGate(
    label: String,
    icon: ImageBitmap?,
    busy: Boolean,
    error: String?,
    biometricEnabled: Boolean,
    lockoutRemaining: () -> Long,
    onSubmit: (String) -> Unit,
    onBiometric: () -> Unit,
    onCancel: () -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var remaining by remember { mutableStateOf(lockoutRemaining()) }
    LaunchedEffect(Unit) {
        while (true) {
            remaining = lockoutRemaining()
            delay(500)
        }
    }
    val locked = remaining > 0
    val fingerprintKey: (@Composable () -> Unit)? =
        if (biometricEnabled) {
            @Composable {
                IconButton(onClick = onBiometric, enabled = !busy, modifier = Modifier.size(72.dp)) {
                    Icon(Icons.Filled.Fingerprint, "Fingerabdruck", Modifier.size(32.dp))
                }
            }
        } else null

    Scaffold { padding ->
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
            ) {
                if (icon != null) {
                    Image(icon, null, Modifier.size(64.dp))
                } else {
                    Icon(Icons.Filled.Lock, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(16.dp))
                Text("$label ist gesperrt", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (biometricEnabled) "Tresor-Code eingeben oder Fingerabdruck verwenden" else "Tresor-Code eingeben",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(24.dp))
                PinPad(pin = pin, onPinChange = { pin = it }, enabled = !busy && !locked, leftKey = fingerprintKey)
                Box(Modifier.height(40.dp), contentAlignment = Alignment.Center) {
                    val msg = if (locked) "Zu viele Fehlversuche – noch ${(remaining + 999) / 1000} s warten." else error
                    if (msg != null) {
                        Text(
                            msg,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                if (busy) {
                    CircularProgressIndicator()
                } else {
                    Button(
                        onClick = { onSubmit(pin); pin = "" },
                        enabled = pin.length >= KeyManager.MIN_PIN_LENGTH && !locked,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Entsperren") }
                }
                TextButton(onClick = onCancel) { Text("Abbrechen") }
            }
        }
    }
}
