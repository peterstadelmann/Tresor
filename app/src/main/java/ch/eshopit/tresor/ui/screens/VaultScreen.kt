package ch.eshopit.tresor.ui.screens

import android.text.format.Formatter
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.eshopit.tresor.ui.AppViewModel
import ch.eshopit.tresor.ui.UiState
import ch.eshopit.tresor.ui.VaultActions
import ch.eshopit.tresor.vault.VaultItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(
    vm: AppViewModel,
    state: UiState,
    snackbar: SnackbarHostState,
    actions: VaultActions
) {
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var addMenu by remember { mutableStateOf(false) }
    var sheetItem by remember { mutableStateOf<VaultItem?>(null) }
    var confirmDelete by remember { mutableStateOf<VaultItem?>(null) }

    val shown = when (filter) {
        1 -> state.items.filter { it.isImage || it.isVideo }
        2 -> state.items.filterNot { it.isImage || it.isVideo }
        else -> state.items
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tresor") },
                actions = {
                    IconButton(onClick = vm::openAppLock) { Icon(Icons.Filled.Apps, "App-Sperre") }
                    IconButton(onClick = vm::openSettings) { Icon(Icons.Filled.Settings, "Einstellungen") }
                    IconButton(onClick = vm::lock) { Icon(Icons.Filled.Lock, "Sperren") }
                }
            )
        },
        floatingActionButton = {
            Box {
                ExtendedFloatingActionButton(
                    onClick = { addMenu = true },
                    icon = { Icon(Icons.Filled.Add, null) },
                    text = { Text("Verschlüsseln") }
                )
                DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Bilder & Videos") },
                        leadingIcon = { Icon(Icons.Filled.PhotoLibrary, null) },
                        onClick = { addMenu = false; actions.import(arrayOf("image/*", "video/*")) }
                    )
                    DropdownMenuItem(
                        text = { Text("Dateien") },
                        leadingIcon = { Icon(Icons.Filled.Description, null) },
                        onClick = { addMenu = false; actions.import(arrayOf("*/*")) }
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = filter) {
                listOf("Alle", "Medien", "Dateien").forEachIndexed { i, label ->
                    Tab(selected = filter == i, onClick = { filter = i }, text = { Text(label) })
                }
            }
            state.busy?.let { BusyBar(it) }

            if (shown.isEmpty()) {
                EmptyState(Modifier.weight(1f))
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(112.dp),
                    contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(shown, key = { it.id }) { item ->
                        ItemCell(
                            item = item,
                            loadThumb = vm::thumbnail,
                            onClick = { if (item.isImage) vm.openViewer(item) else sheetItem = item },
                            onLongClick = { sheetItem = item }
                        )
                    }
                }
            }
        }
    }

    sheetItem?.let { item ->
        ModalBottomSheet(onDismissRequest = { sheetItem = null }) {
            Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.isImage) {
                    SheetAction(Icons.Filled.Visibility, "Anzeigen") { sheetItem = null; vm.openViewer(item) }
                }
                SheetAction(Icons.Filled.OpenInNew, "Öffnen mit …") { sheetItem = null; actions.openWith(item) }
                SheetAction(Icons.Filled.Download, "Entschlüsseln & speichern") { sheetItem = null; actions.export(item) }
                SheetAction(Icons.Filled.Delete, "Löschen", danger = true) { sheetItem = null; confirmDelete = item }
            }
        }
    }

    confirmDelete?.let { item ->
        DeleteDialog(item, onConfirm = { confirmDelete = null; vm.delete(item) }, onDismiss = { confirmDelete = null })
    }
}

@Composable
fun BusyBar(text: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(text, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@Composable
fun DeleteDialog(item: VaultItem, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Endgültig löschen?") },
        text = { Text("„${item.name}“ wird aus dem Tresor gelöscht und kann nicht wiederhergestellt werden.") },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
    val color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    ListItem(
        headlineContent = { Text(label, color = color) },
        leadingContent = { Icon(icon, null, tint = color) },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
private fun EmptyState(modifier: Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.Lock, null,
                Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.height(16.dp))
            Text("Noch nichts im Tresor", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Tippe auf „Verschlüsseln“, um Bilder oder Dateien hinzuzufügen. " +
                    "Sie werden mit AES-256 verschlüsselt und nur in dieser App gespeichert.",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ItemCell(
    item: VaultItem,
    loadThumb: suspend (VaultItem) -> ImageBitmap?,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (item.hasThumb) {
                val thumb by produceState<ImageBitmap?>(null, item.id) { value = loadThumb(item) }
                val bmp = thumb
                if (bmp != null) {
                    Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    if (item.isVideo) {
                        Icon(Icons.Filled.PlayCircle, null, Modifier.size(40.dp), tint = Color.White)
                    }
                } else {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            } else {
                Icon(
                    iconFor(item.mime), null,
                    Modifier.size(44.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(
                item.name,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                Formatter.formatShortFileSize(context, item.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun iconFor(mime: String): ImageVector = when {
    mime.startsWith("image/") -> Icons.Filled.Image
    mime.startsWith("video/") -> Icons.Filled.Movie
    mime.startsWith("audio/") -> Icons.Filled.AudioFile
    mime == "application/pdf" -> Icons.Filled.PictureAsPdf
    mime.contains("zip") || mime.contains("compressed") || mime.contains("tar") -> Icons.Filled.FolderZip
    else -> Icons.Filled.Description
}
