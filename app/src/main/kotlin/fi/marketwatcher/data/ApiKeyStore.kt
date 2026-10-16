package fi.marketwatcher.data

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import java.security.InvalidAlgorithmParameterException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The FRED API key, encrypted with AES-256-GCM. The AES key lives in Android Keystore (secure
 * hardware, StrongBox when the phone has it), can be used only by this app and cannot be copied
 * off the phone. Only "iv:ciphertext" is stored, in its own preferences file, which is excluded
 * from backups and device transfer (res/xml/data_extraction_rules.xml, backup_rules.xml).
 *
 * No screen-unlock requirement: the twice-daily background check must read the key while the
 * phone is locked.
 */
class ApiKeyStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    sealed interface Loaded {
        data class Key(val value: String) : Loaded
        data object None : Loaded
        /** Encrypted text exists but can never be decrypted here (restored elsewhere, keystore reset, tampering). */
        data object Unreadable : Loaded
        /** A temporary keystore problem; nothing is deleted, try again later. */
        data object TryLater : Loaded
    }

    fun has(): Boolean = prefs.contains(PREF)

    private fun keystore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun existingKey(): SecretKey? = keystore().getKey(ALIAS, null) as SecretKey?

    private fun newKey(strongBox: Boolean): SecretKey = try {
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .apply { if (strongBox && Build.VERSION.SDK_INT >= 28) setIsStrongBoxBacked(true) }
                .build())
        }.generateKey()
    } catch (e: Exception) {
        // StrongBoxUnavailableException (API 28+) or a StrongBox that refuses AES-256: use the normal secure hardware.
        if (strongBox && (Build.VERSION.SDK_INT < 28 || e is StrongBoxUnavailableException || e is java.security.ProviderException)) newKey(false)
        else throw e
    }

    /** Encrypts and stores [apiKey]. Synchronous: done when this returns. */
    fun save(apiKey: String): Unit = synchronized(LOCK) {
        val key = existingKey() ?: newKey(strongBox = true)
        val c = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        c.updateAAD(AAD)
        val ct = c.doFinal(apiKey.trim().toByteArray(Charsets.UTF_8))
        val stored = b64(c.iv) + ":" + b64(ct)
        check(prefs.edit().putString(PREF, stored).commit()) { "Could not write the encrypted key." }
    }

    fun load(): Loaded = synchronized(LOCK) {
        val stored = prefs.getString(PREF, null) ?: return Loaded.None
        return try {
            val parts = stored.split(":")
            require(parts.size == 2) { "bad format" }
            val key = existingKey() ?: throw KeyGoneException()
            val c = Cipher.getInstance(TRANSFORMATION)
                .apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, unb64(parts[0]))) }
            c.updateAAD(AAD)
            Loaded.Key(String(c.doFinal(unb64(parts[1])), Charsets.UTF_8))
        } catch (e: Exception) {
            val permanent = when (e) {
                // The encrypted text can never be read on this phone: remove it and ask for the key again.
                is KeyGoneException, is AEADBadTagException, is KeyPermanentlyInvalidatedException,
                is InvalidAlgorithmParameterException, is IllegalArgumentException -> true
                // Android also wraps temporary keystore failures in this one: permanent only if the key is really gone.
                is UnrecoverableKeyException -> runCatching { !keystore().containsAlias(ALIAS) }.getOrDefault(false)
                else -> false
            }
            if (permanent) { clearLocked(); Loaded.Unreadable } else Loaded.TryLater
        }
    }

    fun clear(): Unit = synchronized(LOCK) { clearLocked() }
    private fun clearLocked() { prefs.edit().remove(PREF).commit() }

    private class KeyGoneException : Exception()

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)

    companion object {
        const val FILE = "marketwatcher_secrets"
        private const val PREF = "fred_api_key_enc"
        private const val ALIAS = "marketwatcher_fred_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private val AAD = "fred_api_key".toByteArray(Charsets.UTF_8)
        /** One lock for every instance and for [Settings]: saves, loads, migration and key creation never interleave. */
        val LOCK = Any()
    }
}
