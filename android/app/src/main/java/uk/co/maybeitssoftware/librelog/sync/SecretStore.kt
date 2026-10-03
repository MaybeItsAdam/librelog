package uk.co.maybeitssoftware.librelog.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The small secrets sync needs: the journal key and the Supabase session.
 * An interface so sync logic can be tested with [InMemorySecretStore].
 */
interface SecretStore {
    fun read(item: Item): ByteArray?
    fun write(item: Item, value: ByteArray)
    fun delete(item: Item)

    enum class Item(val key: String) {
        JournalKey("journal-key"),
        Session("supabase-session"),
        /** Not secret, but device-only: ops pulled since the last snapshot. */
        PulledSinceSnapshot("pulled-since-snapshot"),
    }
}

/**
 * Values sealed with AES-GCM under a key that lives in the Android Keystore
 * and can't be exported, then kept in private SharedPreferences. The
 * manifest's backup rules exclude those prefs, so neither the journal key nor
 * the session ever reaches a cloud backup or a device transfer.
 */
class KeystoreSecretStore(context: Context) : SecretStore {
    private val prefs = context.getSharedPreferences("librelog.secrets", Context.MODE_PRIVATE)

    override fun read(item: SecretStore.Item): ByteArray? {
        val stored = prefs.getString(item.key, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_LEN))
            cipher.doFinal(bytes, IV_LEN, bytes.size - IV_LEN)
        }.getOrNull()
    }

    override fun write(item: SecretStore.Item, value: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(value)
        prefs.edit().putString(item.key, Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
    }

    override fun delete(item: SecretStore.Item) {
        prefs.edit().remove(item.key).apply()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
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
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "librelog-secrets"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_LEN = 12
    }
}

class InMemorySecretStore : SecretStore {
    private val values = mutableMapOf<SecretStore.Item, ByteArray>()
    override fun read(item: SecretStore.Item) = values[item]
    override fun write(item: SecretStore.Item, value: ByteArray) {
        values[item] = value
    }
    override fun delete(item: SecretStore.Item) {
        values.remove(item)
    }
}
