package ch.eshopit.tresor.ui.screens

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import ch.eshopit.tresor.ui.AppViewModel
import ch.eshopit.tresor.ui.UiState
import ch.eshopit.tresor.ui.VaultActions
import ch.eshopit.tresor.vault.StorageAccess
import ch.eshopit.tresor.vault.VaultItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(
    vm: AppViewModel,
    state: UiState,
    snackbar: SnackbarHostState,
    actions: VaultActions
) {
    val context = LocalContext.current
    var filter by rememberSaveable { mutableStateOf(0) }
    var currentPath by rememberSaveable { mutableStateOf("") }
    var addMenu by remember { mutableStateOf(false) }
    var sheetItem by remember { mutableStateOf<VaultItem?>(null) }
    var confirmDelete by remember { mutableStateOf<VaultItem?>(null) }
    var folderSheet by remember { mutableStateOf<String?>(null) }
    var restoreFolder by remember { mutableStateOf<String?>(null) }
    var confirmFolderDelete by remember { mutableStateOf<String?>(null) }
    // Mehrfachauswahl: Datei-IDs und Ordner als "dir:<pfad>"
    var selection by remember { mutableStateOf(setOf<String>()) }
    var confirmBulkDelete by remember { mutableStateOf(false) }
    val selectionMode = selection.isNotEmpty()
    fun toggle(key: String) {
        selection = if (key in selection) selection - key else selection + key
    }

    // Berechtigung "Zugriff auf alle Dateien" nach Rückkehr aus den Einstellungen neu prüfen
    var storageGranted by remember { mutableStateOf(StorageAccess.granted(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) storageGranted = StorageAccess.granted(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val allFolders = allFolderPaths(state)
    // Ordner verschwunden (z. B. gelöscht)? Zurück zur obersten Ebene.
    val path = if (currentPath.isEmpty() || currentPath in allFolders) currentPath else ""
    val parentPath = path.substringBeforeLast('/', "")
    BackHandler(enabled = path.isNotEmpty() && !selectionMode) { currentPath = parentPath }
    BackHandler(enabled = selectionMode) { selection = emptySet() }

    val subFolders = allFolders.filter { it.substringBeforeLast('/', "") == path && it.isNotEmpty() }.sorted()
    val filesHere = state.items.filter { it.folder == path }
    val shownFiles = when (filter) {
        1 -> filesHere.filter { it.isImage || it.isVideo }
        2 -> filesHere.filterNot { it.isImage || it.isVideo }
        else -> filesHere
    }

    Scaffold(
        topBar = {
            if (selectionMode) {
                val selectedFolders = selection.filter { it.startsWith("dir:") }.map { it.removePrefix("dir:") }
                val selectedItems = state.items.filter { it.id in selection }
                TopAppBar(
                    title = { Text("${selection.size} ausgewählt") },
                    navigationIcon = {
                        IconButton(onClick = { selection = emptySet() }) { Icon(Icons.Filled.Close, "Auswahl beenden") }
                    },
                    actions = {
                        if (selection.size == 1 && selectedFolders.size == 1) {
                            IconButton(onClick = {
                                restoreFolder = selectedFolders.first()
                                selection = emptySet()
                            }) { Icon(Icons.Filled.Restore, "Ordner wiederherstellen") }
                        }
                        if (selection.size == 1 && selectedItems.size == 1) {
                            IconButton(onClick = {
                                sheetItem = selectedItems.first()
                                selection = emptySet()
                            }) { Icon(Icons.Filled.MoreVert, "Weitere Aktionen") }
                        }
                        IconButton(onClick = {
                            selection = subFolders.map { "dir:$it" }.toSet() + shownFiles.map { it.id }
                        }) { Icon(Icons.Filled.SelectAll, "Alle auswählen") }
                        IconButton(onClick = { confirmBulkDelete = true }) { Icon(Icons.Filled.Delete, "Löschen") }
                    }
                )
            } else TopAppBar(
                title = {
                    Text(
                        if (path.isEmpty()) "Tresor" else path.substringAfterLast('/'),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    if (path.isNotEmpty()) {
                        IconButton(onClick = { currentPath = parentPath }) { Icon(Icons.Filled.ArrowBack, "Zurück") }
                    }
                },
                actions = {
                    if (path.isNotEmpty()) {
                        IconButton(onClick = { folderSheet = path }) { Icon(Icons.Filled.MoreVert, "Ordner-Aktionen") }
                    } else {
                        IconButton(onClick = vm::openAppLock) { Icon(Icons.Filled.Apps, "App-Sperre") }
                        IconButton(onClick = vm::openSettings) { Icon(Icons.Filled.Settings, "Einstellungen") }
                    }
                    IconButton(onClick = vm::lock) { Icon(Icons.Filled.Lock, "Sperren") }
                }
            )
        },
        floatingActionButton = {
            if (path.isEmpty() && !selectionMode) {
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
                        DropdownMenuItem(
                            text = { Text("Ganzer Ordner") },
                            leadingIcon = { Icon(Icons.Filled.Folder, null) },
                            onClick = { addMenu = false; actions.importFolder() }
                        )
                    }
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
            if (path.isNotEmpty()) {
                Text(
                    path.replace("/", "  ›  "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            if (subFolders.isEmpty() && shownFiles.isEmpty()) {
                if (path.isEmpty() && state.items.isEmpty()) {
                    EmptyState(Modifier.weight(1f))
                } else {
                    Box(Modifier.weight(1f).fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text("Dieser Ordner ist leer", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(112.dp),
                    contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(subFolders, key = { "dir:$it" }) { folder ->
                        FolderCell(
                            name = folder.substringAfterLast('/'),
                            fileCount = vm.itemsInFolder(folder).size,
                            selected = "dir:$folder" in selection,
                            onClick = { if (selectionMode) toggle("dir:$folder") else currentPath = folder },
                            onLongClick = { toggle("dir:$folder") }
                        )
                    }
                    items(shownFiles, key = { it.id }) { item ->
                        ItemCell(
                            item = item,
                            loadThumb = vm::thumbnail,
                            selected = item.id in selection,
                            onClick = {
                                when {
                                    selectionMode -> toggle(item.id)
                                    item.isImage -> vm.openViewer(item)
                                    else -> sheetItem = item
                                }
                            },
                            onLongClick = { toggle(item.id) }
                        )
                    }
                }
            }
        }
    }

    sheetItem?.let { item ->
        ModalBottomSheet(onDismissRequest = { sheetItem = null }) {
            Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
                SheetTitle(item.name)
                if (item.isImage) {
                    SheetAction(Icons.Filled.Visibility, "Anzeigen") { sheetItem = null; vm.openViewer(item) }
                }
                SheetAction(Icons.Filled.OpenInNew, "Öffnen mit …") { sheetItem = null; actions.openWith(item) }
                SheetAction(Icons.Filled.Download, "Entschlüsseln & speichern") { sheetItem = null; actions.export(item) }
                SheetAction(Icons.Filled.Delete, "Löschen", danger = true) { sheetItem = null; confirmDelete = item }
            }
        }
    }

    folderSheet?.let { folder ->
        ModalBottomSheet(onDismissRequest = { folderSheet = null }) {
            Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
                SheetTitle(folder.substringAfterLast('/'))
                SheetAction(Icons.Filled.Restore, "Wiederherstellen …") { folderSheet = null; restoreFolder = folder }
                SheetAction(Icons.Filled.Delete, "Ordner löschen", danger = true) {
                    folderSheet = null
                    confirmFolderDelete = folder
                }
            }
        }
    }

    restoreFolder?.let { folder ->
        RestoreDialog(
            folder = folder,
            originalPath = vm.folderRecord(folder)?.origPath,
            fileCount = vm.itemsInFolder(folder).size,
            removeDefault = vm.deleteOriginals,
            storageGranted = storageGranted,
            onRequestAccess = actions.requestStorageAccess,
            onRestoreOriginal = { remove ->
                restoreFolder = null
                if (remove && folder == path) currentPath = parentPath
                vm.restoreToOriginal(folder, remove)
            },
            onChooseTarget = { remove ->
                restoreFolder = null
                actions.exportFolder(folder, remove)
            },
            onDismiss = { restoreFolder = null }
        )
    }

    confirmFolderDelete?.let { folder ->
        AlertDialog(
            onDismissRequest = { confirmFolderDelete = null },
            title = { Text("Ordner löschen?") },
            text = {
                Text(
                    "„${folder.substringAfterLast('/')}“ mit allen ${vm.itemsInFolder(folder).size} Dateien " +
                        "wird endgültig aus dem Tresor gelöscht."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmFolderDelete = null
                    if (folder == path || path.startsWith("$folder/")) currentPath = folder.substringBeforeLast('/', "")
                    vm.deleteFolder(folder)
                }) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmFolderDelete = null }) { Text("Abbrechen") } }
        )
    }

    if (confirmBulkDelete) {
        val folders = selection.filter { it.startsWith("dir:") }.map { it.removePrefix("dir:") }
        val ids = selection.filterNot { it.startsWith("dir:") }.toSet()
        val fileCount = ids.size + folders.sumOf { vm.itemsInFolder(it).size }
        AlertDialog(
            onDismissRequest = { confirmBulkDelete = false },
            title = { Text("Endgültig löschen?") },
            text = {
                Text(buildString {
                    append("$fileCount Datei(en)")
                    if (folders.isNotEmpty()) append(" in ${folders.size} Ordner(n) und einzeln")
                    append(" werden aus dem Tresor gelöscht und können nicht wiederhergestellt werden.")
                })
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmBulkDelete = false
                    selection = emptySet()
                    vm.deleteMany(ids, folders.toSet())
                }) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmBulkDelete = false }) { Text("Abbrechen") } }
        )
    }

    confirmDelete?.let { item ->
        DeleteDialog(item, onConfirm = { confirmDelete = null; vm.delete(item) }, onDismiss = { confirmDelete = null })
    }
}

/** Alle Ordnerpfade im Tresor (aus Ordner-Datensätzen, auch leere Unterordner, und aus Dateien). */
private fun allFolderPaths(state: UiState): Set<String> {
    val result = HashSet<String>()
    fun addWithParents(path: String) {
        var p = path
        while (p.isNotEmpty() && result.add(p)) p = p.substringBeforeLast('/', "")
    }
    state.folders.forEach { record ->
        addWithParents(record.name)
        record.dirs.forEach { addWithParents("${record.name}/$it") }
    }
    state.items.forEach { addWithParents(it.folder) }
    return result
}

@Composable
private fun RestoreDialog(
    folder: String,
    originalPath: String?,
    fileCount: Int,
    removeDefault: Boolean,
    storageGranted: Boolean,
    onRequestAccess: () -> Unit,
    onRestoreOriginal: (remove: Boolean) -> Unit,
    onChooseTarget: (remove: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var remove by remember { mutableStateOf(removeDefault) }
    val canRestoreOriginal = originalPath != null && storageGranted
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("„${folder.substringAfterLast('/')}“ wiederherstellen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("$fileCount Datei(en) werden entschlüsselt – mit allen Unterordnern und Originalnamen.")
                if (originalPath != null) {
                    Text("Originalort:", style = MaterialTheme.typography.labelMedium)
                    Text(originalPath, style = MaterialTheme.typography.bodySmall)
                    if (!storageGranted) {
                        Text(
                            "Für die Wiederherstellung am Originalort braucht Tresor die Berechtigung " +
                                "„Zugriff auf alle Dateien“.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        TextButton(onClick = onRequestAccess) { Text("Berechtigung erteilen") }
                    }
                } else {
                    Text(
                        "Der Originalort ist nicht bekannt – bitte einen Zielordner wählen.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { remove = !remove }
                ) {
                    Checkbox(checked = remove, onCheckedChange = { remove = it })
                    Text("Danach aus dem Tresor entfernen")
                }
            }
        },
        confirmButton = {
            if (canRestoreOriginal) {
                TextButton(onClick = { onRestoreOriginal(remove) }) { Text("Am Originalort") }
            } else {
                TextButton(onClick = { onChooseTarget(remove) }) { Text("Zielordner wählen") }
            }
        },
        dismissButton = {
            Row {
                if (canRestoreOriginal) {
                    TextButton(onClick = { onChooseTarget(remove) }) { Text("Anderer Ort") }
                }
                TextButton(onClick = onDismiss) { Text("Abbrechen") }
            }
        }
    )
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
private fun SheetTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}

@OptIn(ExperimentalMaterial3Api::class)
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
                "Tippe auf „Verschlüsseln“, um Bilder, Dateien oder ganze Ordner hinzuzufügen. " +
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
private fun FolderCell(
    name: String,
    fileCount: Int,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .clip(MaterialTheme.shapes.medium)
            .selectionBorder(selected)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Folder, null,
                Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
            SelectedMark(selected)
        }
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (fileCount == 1) "1 Datei" else "$fileCount Dateien",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(4.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ItemCell(
    item: VaultItem,
    loadThumb: suspend (VaultItem) -> ImageBitmap?,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .clip(MaterialTheme.shapes.medium)
            .selectionBorder(selected)
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
            SelectedMark(selected)
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

@Composable
private fun Modifier.selectionBorder(selected: Boolean): Modifier =
    if (selected) border(3.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium) else this

/** Häkchen oben rechts auf ausgewählten Kacheln (innerhalb einer Box). */
@Composable
private fun SelectedMark(selected: Boolean) {
    if (!selected) return
    Box(Modifier.fillMaxSize().padding(6.dp), contentAlignment = Alignment.TopEnd) {
        Icon(
            Icons.Filled.CheckCircle, "Ausgewählt",
            Modifier
                .size(26.dp)
                .background(MaterialTheme.colorScheme.surface, CircleShape),
            tint = MaterialTheme.colorScheme.primary
        )
    }
}
