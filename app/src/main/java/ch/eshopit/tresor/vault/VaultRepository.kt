package ch.eshopit.tresor.vault

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import ch.eshopit.tresor.crypto.Crypto
import ch.eshopit.tresor.crypto.FileCrypto
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
    val hasThumb: Boolean
) {
    val isImage: Boolean get() = mime.startsWith("image/")
    val isVideo: Boolean get() = mime.startsWith("video/")
}

/**
 * Speichert verschlüsselte Elemente im privaten App-Speicher:
 *   <id>.data  – Inhalt (FileCrypto-Format)
 *   <id>.meta  – Name, Typ, Grösse (AES-GCM)
 *   <id>.thumb – Vorschaubild für Bilder/Videos (AES-GCM, optional)
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
                        hasThumb = json.optBoolean("thumb", false)
                    )
                }.getOrNull()
            }
            .sortedByDescending { it.added }

    /** Verschlüsselt die Datei hinter [uri] in den Tresor. */
    fun import(key: ByteArray, uri: Uri): VaultItem {
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

            val item = VaultItem(id, name, mime, size, System.currentTimeMillis(), thumb != null)
            val json = JSONObject()
                .put("name", item.name)
                .put("mime", item.mime)
                .put("size", item.size)
                .put("added", item.added)
                .put("thumb", item.hasThumb)
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
        private const val MAX_VIEWER_BYTES = 200L * 1024 * 1024
    }
}
