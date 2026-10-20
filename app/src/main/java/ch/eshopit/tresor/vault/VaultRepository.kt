package ch.eshopit.tresor.vault

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import ch.eshopit.tresor.crypto.Crypto
import ch.eshopit.tresor.crypto.FileCrypto
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.math.max

data class VaultItem(
    val id: String,
    val name: String,
    val mime: String,
    val size: Long,
    val added: Long,
    val hasThumb: Boolean,
    /** Ordnerpfad bei Ordner-Import, z. B. "Urlaub/2024"; leer für einzelne Dateien. */
    val folder: String = "",
    /** Ursprüngliches Änderungsdatum der Datei (0 = unbekannt). */
    val mtime: Long = 0L
) {
    val isImage: Boolean get() = mime.startsWith("image/")
    val isVideo: Boolean get() = mime.startsWith("video/")
}

/**
 * Ein ganzer Ordner im Tresor. [name] ist der Name im Tresor (eindeutig), [origName] und
 * [origPath] beschreiben das Original; [dirs] enthält alle Unterordner relativ zum Ordner,
 * auch leere, damit beim Wiederherstellen die Struktur exakt gleich ist.
 */
data class FolderRecord(
    val id: String,
    val name: String,
    val origName: String,
    val origPath: String?,
    val dirs: List<String>,
    val added: Long
)

/**
 * Speichert verschlüsselte Elemente im privaten App-Speicher:
 *   <id>.data  – Inhalt (FileCrypto-Format)
 *   <id>.meta  – Name, Typ, Grösse (AES-GCM)
 *   <id>.thumb – Vorschaubild für Bilder/Videos (AES-GCM, optional)
 *   <id>.dir   – Ordner-Datensatz (AES-GCM)
 * Dateinamen verraten nichts über den Inhalt.
 */
class VaultRepository(private val context: Context) {

    private val dir = File(context.filesDir, "vault").apply { mkdirs() }
    private val sharedDir get() = File(context.cacheDir, "shared")
    private val resolver get() = context.contentResolver

    init {
        // Reste abgebrochener Importe entfernen
        dir.listFiles()?.filter { it.name.endsWith(".tmp") }?.forEach { it.delete() }
    }

    fun list(key: ByteArray): List<VaultItem> =
        dir.listFiles().orEmpty()
            .filter { it.name.endsWith(".meta") }
            .mapNotNull { file ->
                val id = file.name.removeSuffix(".meta")
                runCatching {
                    val json = JSONObject(String(Crypto.open(key, file.readBytes(), aad("meta", id)), Charsets.UTF_8))
                    VaultItem(
                        id = id,
                        name = json.getString("name"),
                        mime = json.getString("mime"),
                        size = json.getLong("size"),
                        added = json.getLong("added"),
                        hasThumb = json.optBoolean("thumb", false),
                        folder = json.optString("folder", ""),
                        mtime = json.optLong("mtime", 0L)
                    )
                }.getOrNull()
            }
            .sortedByDescending { it.added }

    /** Verschlüsselt die Datei hinter [uri] in den Tresor. */
    fun import(key: ByteArray, uri: Uri, folder: String = "", mtime: Long = 0L): VaultItem {
        val name = queryName(uri) ?: "Datei"
        val mime = resolver.getType(uri) ?: guessMime(name)
        val id = UUID.randomUUID().toString()
        val tmp = File(dir, "$id.data.tmp")
        val dataFile = File(dir, "$id.data")
        val thumbFile = File(dir, "$id.thumb")
        try {
            val size = (resolver.openInputStream(uri) ?: throw IOException("Datei nicht lesbar")).use { input ->
                FileOutputStream(tmp).use { fos ->
                    val out = fos.buffered()
                    val n = FileCrypto.encrypt(key, id.toByteArray(), input.buffered(), out)
                    out.flush()
                    fos.fd.sync()
                    n
                }
            }
            if (!tmp.renameTo(dataFile)) throw IOException("Speichern fehlgeschlagen")

            val thumb = runCatching { createThumbnail(uri, mime) }.getOrNull()
            if (thumb != null) {
                thumbFile.writeBytes(Crypto.seal(key, thumb, aad("thumb", id)))
            }

            val item = VaultItem(id, name, mime, size, System.currentTimeMillis(), thumb != null, folder, mtime)
            val json = JSONObject()
                .put("name", item.name)
                .put("mime", item.mime)
                .put("size", item.size)
                .put("added", item.added)
                .put("thumb", item.hasThumb)
                .put("folder", item.folder)
                .put("mtime", item.mtime)
            // Metadaten zuletzt schreiben: erst dann erscheint das Element in der Liste.
            File(dir, "$id.meta").writeBytes(Crypto.seal(key, json.toString().toByteArray(), aad("meta", id)))
            return item
        } catch (e: Exception) {
            tmp.delete(); dataFile.delete(); thumbFile.delete()
            throw e
        }
    }

    /** Löscht die unverschlüsselte Originaldatei, sofern der Anbieter das erlaubt. */
    fun deleteOriginal(uri: Uri): Boolean =
        runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false)

    /** Entschlüsselt ein Element in eine vom Benutzer gewählte Zieldatei. */
    fun export(key: ByteArray, item: VaultItem, dest: Uri) {
        try {
            (resolver.openOutputStream(dest, "w") ?: throw IOException("Ziel nicht beschreibbar")).use { os ->
                val out = os.buffered()
                File(dir, "${item.id}.data").inputStream().buffered().use { input ->
                    FileCrypto.decrypt(key, item.id.toByteArray(), input, out)
                }
                out.flush()
            }
        } catch (e: Exception) {
            // Keine halb entschlüsselte Datei zurücklassen
            runCatching { DocumentsContract.deleteDocument(resolver, dest) }
            throw e
        }
    }

    /** Temporäre Klartext-Kopie für "Öffnen mit …". Wird beim Sperren gelöscht. */
    fun prepareShare(key: ByteArray, item: VaultItem): Uri {
        val folder = File(sharedDir, item.id).apply { mkdirs() }
        val target = File(folder, safeFileName(item.name))
        try {
            target.outputStream().buffered().use { out ->
                File(dir, "${item.id}.data").inputStream().buffered().use { input ->
                    FileCrypto.decrypt(key, item.id.toByteArray(), input, out)
                }
            }
        } catch (e: Exception) {
            target.delete()
            throw e
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
    }

    fun clearShared() {
        sharedDir.deleteRecursively()
    }

    fun thumbnail(key: ByteArray, item: VaultItem): Bitmap? {
        val file = File(dir, "${item.id}.thumb")
        if (!file.exists()) return null
        val bytes = Crypto.open(key, file.readBytes(), aad("thumb", item.id))
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    fun loadImage(key: ByteArray, item: VaultItem, maxDim: Int): Bitmap? {
        if (item.size > MAX_VIEWER_BYTES) throw IOException("Bild zu gross für die Vorschau")
        val out = ByteArrayOutputStream(item.size.toInt().coerceAtLeast(32))
        File(dir, "${item.id}.data").inputStream().buffered().use { input ->
            FileCrypto.decrypt(key, item.id.toByteArray(), input, out)
        }
        val bytes = out.toByteArray()
        try {
            return decodeBytes(bytes, maxDim)
        } finally {
            bytes.fill(0)
        }
    }

    fun delete(item: VaultItem) {
        listOf("meta", "data", "thumb").forEach { File(dir, "${item.id}.$it").delete() }
        File(sharedDir, item.id).deleteRecursively()
    }

    // ---------------------------------------------------------------- Ganze Ordner

    data class TreeFile(val uri: Uri, val rel: String, val mtime: Long)

    data class TreeScan(
        val name: String,
        val rootDocUri: Uri,
        val origPath: String?,
        val files: List<TreeFile>,
        /** Alle Unterordner relativ zum gewählten Ordner, z. B. "2024", "2024/Juli". */
        val dirs: List<String>,
        /** true, wenn Unterordner wegen zu grosser Tiefe übersprungen wurden. */
        val truncated: Boolean
    )

    /** Liest einen gewählten Ordner samt Unterordnern (auch leere) ein. */
    fun scanTree(treeUri: Uri): TreeScan {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootDoc = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
        val name = safeFolderName(queryName(rootDoc) ?: rootId.substringAfterLast(':').substringAfterLast('/'))
        val files = mutableListOf<TreeFile>()
        val dirs = mutableListOf<String>()
        var truncated = false
        val queue = ArrayDeque<Pair<String, String>>()
        queue.add(rootId to "")
        val columns = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_LAST_MODIFIED
        )
        while (queue.isNotEmpty()) {
            val (docId, rel) = queue.removeAt(0)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            resolver.query(children, columns, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val childName = c.getString(1) ?: continue
                    if (c.getString(2) == Document.MIME_TYPE_DIR) {
                        val childRel = if (rel.isEmpty()) safeFolderName(childName) else "$rel/${safeFolderName(childName)}"
                        if (childRel.count { it == '/' } < MAX_FOLDER_DEPTH) {
                            dirs.add(childRel)
                            queue.add(id to childRel)
                        } else {
                            truncated = true
                        }
                    } else {
                        files.add(TreeFile(DocumentsContract.buildDocumentUriUsingTree(treeUri, id), rel, c.getLong(3)))
                    }
                }
            }
            if (files.size > MAX_FOLDER_FILES) throw IOException("Mehr als $MAX_FOLDER_FILES Dateien im Ordner")
        }
        return TreeScan(name, rootDoc, originalPath(treeUri, rootId), files, dirs, truncated)
    }

    /** Löscht den ganzen Originalordner (nach erfolgreicher Übernahme in den Tresor). */
    fun deleteOriginalFolder(rootDocUri: Uri, origPath: String?): Boolean {
        if (runCatching { DocumentsContract.deleteDocument(resolver, rootDocUri) }.getOrDefault(false)) return true
        if (origPath != null && StorageAccess.granted(context)) {
            val folder = File(origPath)
            return folder.deleteRecursively() || !folder.exists()
        }
        return false
    }

    fun listFolders(key: ByteArray): List<FolderRecord> =
        dir.listFiles().orEmpty()
            .filter { it.name.endsWith(".dir") }
            .mapNotNull { file ->
                val id = file.name.removeSuffix(".dir")
                runCatching {
                    val json = JSONObject(String(Crypto.open(key, file.readBytes(), aad("dir", id)), Charsets.UTF_8))
                    val dirs = json.optJSONArray("dirs")
                    FolderRecord(
                        id = id,
                        name = json.getString("name"),
                        origName = json.optString("origName", json.getString("name")),
                        origPath = if (json.has("origPath")) json.getString("origPath") else null,
                        dirs = if (dirs == null) emptyList() else List(dirs.length()) { dirs.getString(it) },
                        added = json.optLong("added", 0L)
                    )
                }.getOrNull()
            }

    fun saveFolder(key: ByteArray, record: FolderRecord) {
        val json = JSONObject()
            .put("name", record.name)
            .put("origName", record.origName)
            .put("dirs", JSONArray(record.dirs))
            .put("added", record.added)
        if (record.origPath != null) json.put("origPath", record.origPath)
        File(dir, "${record.id}.dir").writeBytes(Crypto.seal(key, json.toString().toByteArray(), aad("dir", record.id)))
    }

    fun deleteFolderRecord(record: FolderRecord) {
        File(dir, "${record.id}.dir").delete()
    }

    /**
     * Stellt [items] (alle aus dem Tresor-Ordner [vaultBase]) im Dateisystem unter [targetRoot]
     * wieder her: gleiche Unterordner (auch leere aus [dirs]), gleiche Namen, gleiches
     * Änderungsdatum. Benötigt "Zugriff auf alle Dateien". Gibt die Anzahl Fehler zurück.
     */
    fun restoreToPath(
        key: ByteArray,
        items: List<VaultItem>,
        vaultBase: String,
        targetRoot: File,
        dirs: List<String>,
        onProgress: (Int) -> Unit
    ): Int {
        targetRoot.mkdirs()
        if (!targetRoot.isDirectory) throw IOException("Ordner ${targetRoot.path} kann nicht erstellt werden")
        dirs.forEach { File(targetRoot, it).mkdirs() }
        val written = mutableListOf<String>()
        var failed = 0
        items.forEachIndexed { index, item ->
            onProgress(index)
            val rel = item.folder.removePrefix(vaultBase).removePrefix("/")
            val targetDir = if (rel.isEmpty()) targetRoot else File(targetRoot, rel)
            val ok = runCatching {
                targetDir.mkdirs()
                val target = uniqueFile(targetDir, safeFileName(item.name))
                try {
                    FileOutputStream(target).use { fos ->
                        val out = fos.buffered()
                        File(dir, "${item.id}.data").inputStream().buffered().use { input ->
                            FileCrypto.decrypt(key, item.id.toByteArray(), input, out)
                        }
                        out.flush()
                        fos.fd.sync()
                    }
                } catch (e: Exception) {
                    target.delete()
                    throw e
                }
                if (item.mtime > 0) target.setLastModified(item.mtime)
                written += target.path
            }.isSuccess
            if (!ok) failed++
        }
        // Damit Galerie & Co. die Dateien sofort wieder sehen
        if (written.isNotEmpty()) MediaScannerConnection.scanFile(context, written.toTypedArray(), null, null)
        return failed
    }

    /**
     * Entschlüsselt [items] (aus dem Tresor-Ordner [vaultBase]) in einen vom Benutzer gewählten
     * Zielordner. Dort wird der Ordner [targetName] samt Unterordnern ([extraDirs] auch leer) angelegt.
     */
    fun exportToTree(
        key: ByteArray,
        items: List<VaultItem>,
        vaultBase: String,
        targetName: String,
        extraDirs: List<String>,
        treeUri: Uri,
        onProgress: (Int) -> Unit
    ): Int {
        val root = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        val dirs = HashMap<String, Uri>()
        dirs[""] = root
        fun dir(path: String): Uri {
            dirs[path]?.let { return it }
            val parent = dir(path.substringBeforeLast('/', ""))
            val created = DocumentsContract.createDocument(
                resolver, parent, Document.MIME_TYPE_DIR, path.substringAfterLast('/')
            ) ?: throw IOException("Ordner konnte nicht erstellt werden")
            dirs[path] = created
            return created
        }
        fun join(a: String, b: String) = if (a.isEmpty()) b else if (b.isEmpty()) a else "$a/$b"
        dir(targetName)
        extraDirs.forEach { runCatching { dir(join(targetName, it)) } }
        var failed = 0
        items.forEachIndexed { index, item ->
            onProgress(index)
            val ok = runCatching {
                val rel = item.folder.removePrefix(vaultBase).removePrefix("/")
                val target = dir(join(targetName, rel))
                val file = DocumentsContract.createDocument(resolver, target, item.mime, item.name)
                    ?: throw IOException("Datei konnte nicht erstellt werden")
                export(key, item, file)
            }.isSuccess
            if (!ok) failed++
        }
        return failed
    }

    /** Leitet aus einem SAF-Ordner den echten Dateipfad ab (nur für den internen Speicher/SD-Karte). */
    private fun originalPath(treeUri: Uri, docId: String): String? = when (treeUri.authority) {
        "com.android.externalstorage.documents" -> {
            val volume = docId.substringBefore(':')
            val rel = docId.substringAfter(':', "")
            @Suppress("DEPRECATION")
            val primary = Environment.getExternalStorageDirectory().path
            val base = when {
                volume.equals("primary", ignoreCase = true) -> primary
                volume == "home" -> "$primary/Documents"
                else -> "/storage/$volume"
            }
            if (rel.isEmpty()) base else "$base/$rel"
        }
        "com.android.providers.downloads.documents" ->
            if (docId.startsWith("raw:")) docId.removePrefix("raw:") else null
        else -> null
    }

    private fun uniqueFile(folder: File, name: String): File {
        var file = File(folder, name)
        if (!file.exists()) return file
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty() || it == name) "" else ".$it" }
        var n = 1
        while (file.exists()) {
            file = File(folder, "$base ($n)$ext")
            n++
        }
        return file
    }

    private fun safeFolderName(name: String): String =
        name.replace('/', '_').trim().ifBlank { "Ordner" }

    // ---------------------------------------------------------------- Hilfsfunktionen

    private fun aad(kind: String, id: String) = "$kind:$id".toByteArray()

    private fun queryName(uri: Uri): String? =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    private fun guessMime(name: String): String =
        MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"

    private fun safeFileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").take(120).ifBlank { "datei" }

    private fun createThumbnail(uri: Uri, mime: String): ByteArray? {
        val bitmap = when {
            mime.startsWith("image/") -> decodeUri(uri, THUMB_SIZE)
            mime.startsWith("video/") -> videoFrame(uri)
            else -> null
        } ?: return null
        return ByteArrayOutputStream().use { bos ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, bos)
            bitmap.recycle()
            bos.toByteArray()
        }
    }

    private fun videoFrame(uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val frame = retriever.frameAtTime ?: return null
            val scale = THUMB_SIZE.toFloat() / max(frame.width, frame.height)
            if (scale >= 1f) return frame
            val scaled = Bitmap.createScaledBitmap(
                frame,
                (frame.width * scale).toInt().coerceAtLeast(1),
                (frame.height * scale).toInt().coerceAtLeast(1),
                true
            )
            if (scaled != frame) frame.recycle()
            return scaled
        } finally {
            retriever.release()
        }
    }

    private fun decodeUri(uri: Uri, maxDim: Int): Bitmap? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            decodeWithImageDecoder(ImageDecoder.createSource(resolver, uri), maxDim)
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxDim)
            }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }

    private fun decodeBytes(bytes: ByteArray, maxDim: Int): Bitmap? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            decodeWithImageDecoder(ImageDecoder.createSource(ByteBuffer.wrap(bytes)), maxDim)
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxDim)
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }

    /** ImageDecoder berücksichtigt auch die EXIF-Ausrichtung von Fotos. */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun decodeWithImageDecoder(source: ImageDecoder.Source, maxDim: Int): Bitmap =
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val scale = max(w, h).toFloat() / maxDim
            if (scale > 1f) {
                decoder.setTargetSize(
                    (w / scale).toInt().coerceAtLeast(1),
                    (h / scale).toInt().coerceAtLeast(1)
                )
            }
        }

    private fun sampleSize(w: Int, h: Int, maxDim: Int): Int {
        var sample = 1
        while (max(w, h) / (sample * 2) >= maxDim) sample *= 2
        return sample
    }

    companion object {
        private const val THUMB_SIZE = 384
        private const val MAX_FOLDER_DEPTH = 20
        private const val MAX_FOLDER_FILES = 5000
        private const val MAX_VIEWER_BYTES = 200L * 1024 * 1024
    }
}
