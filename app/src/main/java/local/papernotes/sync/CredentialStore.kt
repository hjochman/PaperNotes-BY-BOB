package local.papernotes.sync

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Das App-Passwort, verschlüsselt mit einem Schlüssel im Android-Keystore (AES-256-GCM, der Schlüssel verlässt das Gerät
 * nicht). Im privaten App-Speicher liegt nur der verschlüsselte Wert. Nicht lesbar (z. B. Schlüssel verloren) heißt: nicht
 * angemeldet; es wird nie ein Klartext-Ersatz gespeichert. Es wird nichts geloggt.
 */
internal class CredentialStore(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun save(secret: String) {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val encrypted = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
            val value = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP)
            prefs.edit().putString(KEY_SECRET, value).commit()
        } catch (e: GeneralSecurityException) {
            clear()
        }
    }

    fun load(): String? {
        val value = prefs.getString(KEY_SECRET, null) ?: return null
        return try {
            val (iv, data) = value.split(':', limit = 2).let { it[0] to it[1] }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, Base64.decode(iv, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            null
        } catch (e: RuntimeException) {
            null
        }
    }

    fun clear() {
        prefs.edit().remove(KEY_SECRET).commit()
        try {
            KeyStore.getInstance(PROVIDER).apply { load(null) }.deleteEntry(ALIAS)
        } catch (e: GeneralSecurityException) {
            // Kein Schlüssel vorhanden oder nicht löschbar: der verschlüsselte Wert ist ohnehin entfernt.
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val FILE = "sync_credentials"
        const val KEY_SECRET = "secret"
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "papernotes_sync_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}
