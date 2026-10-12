package com.arcrictor.hermes.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.arcrictor.hermes.gateway.TokenStorage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.tokenDataStore by preferencesDataStore("tokens")

/**
 * The token set, AES-256-GCM-encrypted with a non-exportable AndroidKeyStore key.
 * Ciphertext (IV + body, base64) lives in DataStore; the password is never stored.
 * See SPEC.md R1.3.
 */
class TokenStore(private val context: Context) : TokenStorage {

    @Serializable
    data class TokenSet(
        val accessToken: String,
        val refreshToken: String,
        val provider: String,
        val userId: String,
        /** Unix seconds. */
        val expiresAt: Long,
        val baseUrl: String,
    )

    companion object {
        private const val KEY_ALIAS = "hermes_tokens_v1"
        private val ENC_TOKENS = stringPreferencesKey("tokens_enc")
        private val BASE_URL = stringPreferencesKey("base_url")
        private val SAVED_USER = stringPreferencesKey("saved_user")
    }

    override val tokens: Flow<TokenSet?> = context.tokenDataStore.data.map { prefs ->
        val enc = prefs[ENC_TOKENS] ?: return@map null
        val base = prefs[BASE_URL] ?: return@map null
        try {
            val set = Json.decodeFromString<TokenSet>(decrypt(enc))
            set.copy(baseUrl = base)
        } catch (_: Exception) {
            null // corrupt entry: treat as signed out
        }
    }

    val savedBaseUrl: Flow<String?> = context.tokenDataStore.data.map { it[BASE_URL] }
    val savedUser: Flow<String?> = context.tokenDataStore.data.map { it[SAVED_USER] }

    override suspend fun save(set: TokenSet) {
        context.tokenDataStore.edit {
            it[ENC_TOKENS] = encrypt(Json.encodeToString(TokenSet.serializer(), set.copy(baseUrl = "")))
            it[BASE_URL] = set.baseUrl
            it[SAVED_USER] = set.userId
        }
    }

    override suspend fun clear() {
        context.tokenDataStore.edit { it.remove(ENC_TOKENS) }
    }

    /** Loaded once per process; KeyStore lookups are slow. */
    private val secretKey: SecretKey by lazy { loadOrCreateKey() }

    private fun loadOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, secretKey)
        }
        val iv = cipher.iv
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + body, Base64.NO_WRAP)
    }

    private fun decrypt(enc: String): String {
        val raw = Base64.decode(enc, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, raw, 0, 12))
        }
        return cipher.doFinal(raw, 12, raw.size - 12).toString(Charsets.UTF_8)
    }
}
