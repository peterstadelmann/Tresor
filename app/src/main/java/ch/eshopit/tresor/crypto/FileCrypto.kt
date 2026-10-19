package ch.eshopit.tresor.crypto

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Streaming-Verschlüsselung für beliebig grosse Dateien (AES-256-GCM in 64-KiB-Blöcken).
 *
 * Format:
 *   Header  = "TRV1" (4 Byte) || Nonce-Präfix (8 Byte, zufällig pro Datei)
 *   Blöcke  = je GCM(Klartext ≤ 64 KiB) inkl. 16-Byte-Tag
 *   Nonce   = Präfix || Blockzähler (4 Byte, big endian)
 *   AAD     = Header || Kontext || Blockzähler || Letzter-Block-Flag
 *
 * Jeder Block ist einzeln authentifiziert. Das Flag für den letzten Block verhindert,
 * dass eine Datei unbemerkt gekürzt wird; der Kontext (Datei-ID) verhindert das
 * Vertauschen von Dateien.
 */
object FileCrypto {
    private val MAGIC = byteArrayOf(0x54, 0x52, 0x56, 0x31) // "TRV1"
    private const val PREFIX_LEN = 8
    private const val HEADER_LEN = 4 + PREFIX_LEN
    const val CHUNK = 64 * 1024
    private const val TAG = 16
    private const val MAX_CHUNKS = 0xFFFFFFFFL

    /** Verschlüsselt [input] nach [output]. Gibt die Anzahl Klartext-Bytes zurück. */
    fun encrypt(
        key: ByteArray,
        context: ByteArray,
        input: InputStream,
        output: OutputStream,
        onProgress: (Long) -> Unit = {}
    ): Long {
        val prefix = Crypto.randomBytes(PREFIX_LEN)
        val header = MAGIC + prefix
        output.write(header)

        val keySpec = SecretKeySpec(key, "AES")
        val src = PushbackInputStream(input, 1)
        val buf = ByteArray(CHUNK)
        var counter = 0L
        var total = 0L
        try {
            while (true) {
                val n = readFully(src, buf, CHUNK)
                val last = n < CHUNK || atEof(src)
                val cipher = Cipher.getInstance(Crypto.GCM)
                cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(Crypto.TAG_BITS, nonce(prefix, counter)))
                cipher.updateAAD(aad(header, context, counter, last))
                output.write(cipher.doFinal(buf, 0, n))
                total += n
                onProgress(total)
                if (last) break
                counter++
                if (counter > MAX_CHUNKS) throw IOException("Datei zu gross")
            }
        } finally {
            buf.fill(0)
        }
        return total
    }

    /** Entschlüsselt [input] nach [output]. Wirft eine Exception, wenn die Daten manipuliert wurden. */
    fun decrypt(
        key: ByteArray,
        context: ByteArray,
        input: InputStream,
        output: OutputStream
    ): Long {
        val header = ByteArray(HEADER_LEN)
        if (readFully(input, header, HEADER_LEN) != HEADER_LEN ||
            !header.copyOfRange(0, 4).contentEquals(MAGIC)
        ) throw IOException("Keine Tresor-Datei")
        val prefix = header.copyOfRange(4, HEADER_LEN)

        val keySpec = SecretKeySpec(key, "AES")
        val src = PushbackInputStream(input, 1)
        val buf = ByteArray(CHUNK + TAG)
        var counter = 0L
        var total = 0L
        while (true) {
            val n = readFully(src, buf, buf.size)
            if (n < TAG) throw IOException("Datei beschädigt")
            val last = n < buf.size || atEof(src)
            val cipher = Cipher.getInstance(Crypto.GCM)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(Crypto.TAG_BITS, nonce(prefix, counter)))
            cipher.updateAAD(aad(header, context, counter, last))
            val plain = cipher.doFinal(buf, 0, n)
            output.write(plain)
            total += plain.size
            plain.fill(0)
            if (last) break
            counter++
            if (counter > MAX_CHUNKS) throw IOException("Datei beschädigt")
        }
        return total
    }

    private fun nonce(prefix: ByteArray, counter: Long): ByteArray =
        ByteBuffer.allocate(Crypto.IV_LEN).put(prefix).putInt(counter.toInt()).array()

    private fun aad(header: ByteArray, context: ByteArray, counter: Long, last: Boolean): ByteArray =
        ByteBuffer.allocate(header.size + context.size + 5)
            .put(header)
            .put(context)
            .putInt(counter.toInt())
            .put(if (last) 1 else 0)
            .array()

    private fun readFully(input: InputStream, buf: ByteArray, len: Int): Int {
        var off = 0
        while (off < len) {
            val r = input.read(buf, off, len - off)
            if (r < 0) break
            off += r
        }
        return off
    }

    private fun atEof(src: PushbackInputStream): Boolean {
        val b = src.read()
        if (b < 0) return true
        src.unread(b)
        return false
    }
}
