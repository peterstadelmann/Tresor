package ch.eshopit.tresor.crypto

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import kotlin.math.max
import kotlin.math.min

/**
 * Verwaltet den Datenschlüssel (Master-Key, 256 Bit, zufällig).
 *
 * Der Master-Key wird nie im Klartext gespeichert, sondern zweifach "eingepackt":
 *  1. mit dem Code: PBKDF2-HMAC-SHA256(Code, Salt) → AES-GCM; das Ergebnis wird
 *     zusätzlich mit einem nicht exportierbaren Android-Keystore-Schlüssel verschlüsselt.
 *     Ein Brute-Force-Angriff auf den Code ist damit nur auf diesem Gerät möglich.
 *  2. optional mit einem Keystore-Schlüssel, der nur nach erfolgreicher biometrischer
 *     Authentifizierung (Fingerabdruck, Klasse "stark") nutzbar ist.
 */
class KeyManager(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    val isSetUp: Boolean get() = prefs.contains(K_PIN_BLOB)
    val isBiometricEnabled: Boolean get() = prefs.contains(K_BIO_BLOB)

    // ---------------------------------------------------------------- Code (PIN)

    /** Erstellt einen neuen Tresor und gibt den Master-Key zurück. */
    fun setup(pin: CharArray): ByteArray {
        val master = Crypto.randomBytes(32)
        wrapWithPin(pin, master)
        resetFailures()
        return master
    }

    fun changePin(newPin: CharArray, master: ByteArray) = wrapWithPin(newPin, master)

    /** Gibt den Master-Key zurück oder `null`, wenn der Code falsch ist. */
    fun unwrapWithPin(pin: CharArray): ByteArray? {
        val salt = prefs.getString(K_SALT, null)?.let(::b64d) ?: return null
        val outer = prefs.getString(K_PIN_BLOB, null)?.let(::b64d) ?: return null
        val iterations = prefs.getInt(K_ITER, PBKDF2_ITERATIONS)
        val inner = deviceOpen(outer)
        val kek = derive(pin, salt, iterations)
        return try {
            Crypto.open(kek, inner, AAD_PIN)
        } catch (e: AEADBadTagException) {
            null
        } finally {
            kek.fill(0)
        }
    }

    private fun wrapWithPin(pin: CharArray, master: ByteArray) {
        val salt = Crypto.randomBytes(16)
        val kek = derive(pin, salt, PBKDF2_ITERATIONS)
        try {
            val inner = Crypto.seal(kek, master, AAD_PIN)
            val outer = deviceSeal(inner)
            prefs.edit()
                .putString(K_SALT, b64(salt))
                .putInt(K_ITER, PBKDF2_ITERATIONS)
                .putString(K_PIN_BLOB, b64(outer))
                .commit()
        } finally {
            kek.fill(0)
        }
    }

    private fun derive(pin: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin, salt, iterations, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    // ---------------------------------------------------------------- Fehlversuche

    fun lockoutRemainingMs(): Long =
        max(0L, prefs.getLong(K_LOCK_UNTIL, 0L) - System.currentTimeMillis())

    fun attemptsBeforeLockout(): Int = max(0, FREE_ATTEMPTS - prefs.getInt(K_FAILED, 0))

    /** Registriert einen Fehlversuch und gibt die Sperrzeit in ms zurück (0 = keine Sperre). */
    fun registerFailure(): Long {
        val failed = prefs.getInt(K_FAILED, 0) + 1
        var delay = 0L
        if (failed >= FREE_ATTEMPTS) {
            val step = min(failed - FREE_ATTEMPTS, 7)
            delay = min(30_000L shl step, 60 * 60 * 1000L) // 30 s, 1 min, 2 min … max. 1 h
        }
        prefs.edit()
            .putInt(K_FAILED, failed)
            .putLong(K_LOCK_UNTIL, if (delay > 0) System.currentTimeMillis() + delay else 0L)
            .commit()
        return delay
    }

    fun resetFailures() {
        prefs.edit().putInt(K_FAILED, 0).putLong(K_LOCK_UNTIL, 0L).apply()
    }

    // ---------------------------------------------------------------- Biometrie

    /** Erzeugt einen neuen biometriegebundenen Schlüssel und einen Cipher zum Einpacken. */
    fun biometricEncryptCipher(): Cipher {
        deleteKey(ALIAS_BIO)
        val builder = KeyGenParameterSpec.Builder(
            ALIAS_BIO,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(builder.build())
        val key = generator.generateKey()
        return Cipher.getInstance(Crypto.GCM).apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    /** Nach erfolgreicher Authentifizierung: Master-Key mit dem freigegebenen Cipher einpacken. */
    fun finishBiometricEnable(cipher: Cipher, master: ByteArray) {
        val blob = cipher.doFinal(master)
        prefs.edit()
            .putString(K_BIO_IV, b64(cipher.iv))
            .putString(K_BIO_BLOB, b64(blob))
            .commit()
    }

    /**
     * Cipher zum Auspacken. Wirft [KeyPermanentlyInvalidatedException], wenn sich die
     * registrierten Fingerabdrücke geändert haben – dann muss Biometrie neu aktiviert werden.
     */
    fun biometricDecryptCipher(): Cipher {
        val key = keyStore.getKey(ALIAS_BIO, null) as? SecretKey
            ?: throw KeyPermanentlyInvalidatedException()
        val iv = prefs.getString(K_BIO_IV, null)?.let(::b64d)
            ?: throw KeyPermanentlyInvalidatedException()
        return Cipher.getInstance(Crypto.GCM).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(Crypto.TAG_BITS, iv))
        }
    }

    fun finishBiometricUnlock(cipher: Cipher): ByteArray {
        val blob = prefs.getString(K_BIO_BLOB, null)?.let(::b64d)
            ?: throw IllegalStateException("Biometrie nicht eingerichtet")
        return cipher.doFinal(blob)
    }

    fun disableBiometric() {
        deleteKey(ALIAS_BIO)
        prefs.edit().remove(K_BIO_IV).remove(K_BIO_BLOB).commit()
    }

    // ---------------------------------------------------------------- Geräteschlüssel

    private fun deviceKey(): SecretKey {
        (keyStore.getKey(ALIAS_DEVICE, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS_DEVICE,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun deviceSeal(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(Crypto.GCM)
        cipher.init(Cipher.ENCRYPT_MODE, deviceKey()) // IV wird vom Keystore erzeugt
        return cipher.iv + cipher.doFinal(data)
    }

    private fun deviceOpen(blob: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(Crypto.GCM)
        cipher.init(
            Cipher.DECRYPT_MODE,
            deviceKey(),
            GCMParameterSpec(Crypto.TAG_BITS, blob, 0, Crypto.IV_LEN)
        )
        return cipher.doFinal(blob, Crypto.IV_LEN, blob.size - Crypto.IV_LEN)
    }

    private fun deleteKey(alias: String) {
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun b64d(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    companion object {
        const val MIN_PIN_LENGTH = 6
        const val MAX_PIN_LENGTH = 12
        private const val PBKDF2_ITERATIONS = 150_000
        private const val FREE_ATTEMPTS = 5

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS_DEVICE = "tresor_device_key"
        private const val ALIAS_BIO = "tresor_biometric_key"
        private val AAD_PIN = "tresor-pin-v1".toByteArray()

        private const val PREFS = "tresor_keys"
        private const val K_SALT = "pin_salt"
        private const val K_ITER = "pin_iterations"
        private const val K_PIN_BLOB = "pin_blob"
        private const val K_BIO_IV = "bio_iv"
        private const val K_BIO_BLOB = "bio_blob"
        private const val K_FAILED = "failed_attempts"
        private const val K_LOCK_UNTIL = "lockout_until"
    }
}
