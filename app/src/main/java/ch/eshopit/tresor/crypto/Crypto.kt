package ch.eshopit.tresor.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Kleine Helfer für AES-256-GCM mit Software-Schlüsseln (z. B. Datenschlüssel, PIN-Schlüssel). */
object Crypto {
    const val GCM = "AES/GCM/NoPadding"
    const val IV_LEN = 12
    const val TAG_BITS = 128

    private val random = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    /** Verschlüsselt [plain]; Ergebnis = IV (12 Byte) || Chiffrat || Tag. */
    fun seal(key: ByteArray, plain: ByteArray, aad: ByteArray? = null): ByteArray {
        val iv = randomBytes(IV_LEN)
        val cipher = Cipher.getInstance(GCM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        if (aad != null) cipher.updateAAD(aad)
        return iv + cipher.doFinal(plain)
    }

    /** Gegenstück zu [seal]. Wirft AEADBadTagException bei falschem Schlüssel oder Manipulation. */
    fun open(key: ByteArray, blob: ByteArray, aad: ByteArray? = null): ByteArray {
        require(blob.size >= IV_LEN + TAG_BITS / 8) { "Daten zu kurz" }
        val cipher = Cipher.getInstance(GCM)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, blob, 0, IV_LEN)
        )
        if (aad != null) cipher.updateAAD(aad)
        return cipher.doFinal(blob, IV_LEN, blob.size - IV_LEN)
    }
}
