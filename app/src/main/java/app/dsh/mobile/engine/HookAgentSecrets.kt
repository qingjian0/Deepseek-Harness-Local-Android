package app.dsh.mobile.engine

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the Hook API key encrypted with an Android Keystore AES-GCM key.
 * The plaintext key is exposed only to the engine child-process environment at spawn.
 */
object HookAgentSecrets {
    private const val PREFS = "dsh_hook_agent_secrets"
    private const val KEY_ALIAS = "dsh.hook.agent.api-key.v1"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_CIPHER_TEXT = "api_key_ciphertext"
    private const val KEY_IV = "api_key_iv"
    private const val DEFAULT_BASE_URL = "http://127.0.0.1:8766"

    data class Config(val baseUrl: String, val apiKey: String)

    fun read(context: Context): Config? = runCatching {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val encrypted = prefs.getString(KEY_CIPHER_TEXT, null) ?: return null
        val iv = Base64.decode(prefs.getString(KEY_IV, null) ?: return null, Base64.NO_WRAP)
        val cipherText = Base64.decode(encrypted, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        val key = String(cipher.doFinal(cipherText), Charsets.UTF_8)
        val base = validateBaseUrl(prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL)
        if (key.length !in 16..512 || key.any { it == '\n' || it == '\r' }) return null
        Config(base, key)
    }.getOrNull()

    fun save(context: Context, baseUrl: String, apiKey: String) {
        val base = validateBaseUrl(baseUrl)
        require(apiKey.length in 16..512 && apiKey.none { it == '\n' || it == '\r' }) {
            "API Key must contain 16–512 characters and no line breaks"
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_BASE_URL, base)
            .putString(KEY_CIPHER_TEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    fun environment(context: Context): List<String> = runCatching {
        if (!ProfilePluginProvisioner.isEnabled(context, ProfilePluginProvisioner.DSH_GPT_HOOK_AGENT_PKG)) {
            return@runCatching emptyList()
        }
        val config = read(context) ?: return@runCatching emptyList()
        listOf(
            "DSH_GPT_HOOK_BASE_URL=${config.baseUrl}",
            "DSH_GPT_HOOK_API_KEY=${config.apiKey}",
        )
    }.getOrDefault(emptyList())

    fun validateBaseUrl(value: String): String {
        val uri = URI(value.trim())
        val host = uri.host?.lowercase()?.removePrefix("[")?.removeSuffix("]")
        require(uri.scheme == "http" && (host == "127.0.0.1" || host == "localhost" || host == "::1")) {
            "Hook API must use HTTP loopback only"
        }
        require(uri.userInfo == null && (uri.path.isNullOrEmpty() || uri.path == "/") &&
            uri.query == null && uri.fragment == null) {
            "Hook API URL must not contain credentials, path, query or fragment"
        }
        val port = uri.port
        require(port == -1 || port in 1..65535) { "Invalid Hook API port" }
        return "http://${if (host == "::1") "[::1]" else host}:${if (port == -1) 8766 else port}"
    }

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }
}
