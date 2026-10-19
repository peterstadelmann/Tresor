package ch.eshopit.tresor.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import ch.eshopit.tresor.ui.AppViewModel
import ch.eshopit.tresor.ui.VaultActions
import ch.eshopit.tresor.vault.VaultItem

private sealed interface LoadState {
    data object Loading : LoadState
    data object Failed : LoadState
    data class Ready(val bitmap: ImageBitmap) : LoadState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    vm: AppViewModel,
    item: VaultItem,
    busy: String?,
    snackbar: SnackbarHostState,
    actions: VaultActions,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    var confirmDelete by remember { mutableStateOf(false) }
    var scale by remember(item.id) { mutableFloatStateOf(1f) }
    var offset by remember(item.id) { mutableStateOf(Offset.Zero) }

    val load by produceState<LoadState>(LoadState.Loading, item.id) {
        value = vm.loadFullImage(item)?.let { LoadState.Ready(it) } ?: LoadState.Failed
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") }
                },
                actions = {
                    IconButton(onClick = { actions.openWith(item) }) { Icon(Icons.Filled.OpenInNew, "Öffnen mit") }
                    IconButton(onClick = { actions.export(item) }) { Icon(Icons.Filled.Download, "Entschlüsseln & speichern") }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Löschen") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            busy?.let { BusyBar(it) }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                when (val s = load) {
                    LoadState.Loading -> CircularProgressIndicator()
                    LoadState.Failed -> Text("Vorschau nicht möglich", color = Color.White)
                    is LoadState.Ready -> Image(
                        bitmap = s.bitmap,
                        contentDescription = item.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(item.id) {
                                detectTapGestures(onDoubleTap = {
                                    if (scale > 1f) {
                                        scale = 1f; offset = Offset.Zero
                                    } else {
                                        scale = 2.5f
                                    }
                                })
                            }
                            .pointerInput(item.id) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    scale = (scale * zoom).coerceIn(1f, 6f)
                                    offset = if (scale == 1f) Offset.Zero else offset + pan
                                }
                            }
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offset.x,
                                translationY = offset.y
                            )
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        DeleteDialog(
            item,
            onConfirm = { confirmDelete = false; vm.delete(item) },
            onDismiss = { confirmDelete = false }
        )
    }
}
