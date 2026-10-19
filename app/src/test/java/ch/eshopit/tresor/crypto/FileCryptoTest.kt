package ch.eshopit.tresor.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class FileCryptoTest {

    private val key = Crypto.randomBytes(32)
    private val ctx = "item-1".toByteArray()

    private fun encrypt(plain: ByteArray, c: ByteArray = ctx): ByteArray =
        ByteArrayOutputStream().also { FileCrypto.encrypt(key, c, ByteArrayInputStream(plain), it) }.toByteArray()

    private fun decrypt(blob: ByteArray, k: ByteArray = key, c: ByteArray = ctx): ByteArray =
        ByteArrayOutputStream().also { FileCrypto.decrypt(k, c, ByteArrayInputStream(blob), it) }.toByteArray()

    @Test
    fun roundTripVariousSizes() {
        val c = FileCrypto.CHUNK
        for (size in listOf(0, 1, 100, c - 1, c, c + 1, 2 * c, 3 * c + 17)) {
            val plain = Crypto.randomBytes(size)
            val blob = encrypt(plain)
            val chunks = if (size == 0) 1 else (size + c - 1) / c
            assertEquals("Grösse $size", 12 + size + 16 * chunks, blob.size)
            assertArrayEquals("Grösse $size", plain, decrypt(blob))
        }
    }

    @Test
    fun wrongKeyFails() = expectFailure { decrypt(encrypt(ByteArray(10)), k = Crypto.randomBytes(32)) }

    @Test
    fun wrongContextFails() = expectFailure { decrypt(encrypt(ByteArray(10)), c = "item-2".toByteArray()) }

    @Test
    fun tamperingFails() = expectFailure {
        val blob = encrypt(ByteArray(1000))
        blob[500] = (blob[500].toInt() xor 1).toByte()
        decrypt(blob)
    }

    @Test
    fun truncationAtChunkBoundaryFails() = expectFailure {
        val blob = encrypt(Crypto.randomBytes(2 * FileCrypto.CHUNK))
        decrypt(blob.copyOf(12 + FileCrypto.CHUNK + 16)) // nur erster Block
    }

    private fun expectFailure(block: () -> Unit) {
        try {
            block()
            fail("Exception erwartet")
        } catch (e: Exception) {
            // erwartet
        }
    }
}
