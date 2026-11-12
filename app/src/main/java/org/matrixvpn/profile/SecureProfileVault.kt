package org.matrixvpn.profile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SavedVpnProfile(
    val id: String,
    val name: String,
    val provider: String,
    val format: String,
    val createdAt: Long
)

/** AES-GCM profile vault. VPN keys are encrypted with a key held by Android Keystore. */
object SecureProfileVault {
    private const val PREFS = "matrix_profile_vault"
    private const val ITEMS_KEY = "encrypted_profiles"
    private const val ACTIVE_KEY = "active_profile_id"
    private const val KEY_ALIAS = "matrix_profile_vault_aes_gcm_v1"
    private const val CIPHER = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128
    private const val MAX_PROFILES = 40
    private const val MAX_PROFILE_CHARS = 2_000_000

    @Synchronized
    fun save(context: Context, name: String, provider: String, profileText: String): SavedVpnProfile {
        require(profileText.isNotBlank() && profileText.length <= MAX_PROFILE_CHARS) {
            "Профиль пустой или слишком большой."
        }
        val inspected = VpnProfileInspector.inspect(profileText)
        require(
            inspected.format == ImportedVpnProfile.Format.WIREGUARD ||
                inspected.format == ImportedVpnProfile.Format.AMNEZIAWG ||
                inspected.format == ImportedVpnProfile.Format.OPENVPN
        ) { "Формат профиля не поддерживается встроенными движками MATRIX." }

        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(profileText.toByteArray(Charsets.UTF_8))
        val id = UUID.randomUUID().toString()
        val createdAt = System.currentTimeMillis()
        val safeName = name.trim().replace(Regex("[\\r\\n\\t]"), " ").take(72).ifBlank { "VPN профиль" }
        val safeProvider = provider.trim().take(40).ifBlank { "Импорт" }
        val item = JSONObject()
            .put("name", safeName)
            .put("provider", safeProvider)
            .put("format", inspected.format.name)
            .put("createdAt", createdAt)
            .put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP))

        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val root = try { JSONObject(prefs.getString(ITEMS_KEY, "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        root.put(id, item)
        if (root.length() > MAX_PROFILES) {
            val oldest = root.keys().asSequence()
                .filter { it != id }
                .sortedBy { root.optJSONObject(it)?.optLong("createdAt") ?: Long.MAX_VALUE }
                .take(root.length() - MAX_PROFILES)
                .toList()
            oldest.forEach { root.remove(it) }
        }
        check(prefs.edit().putString(ITEMS_KEY, root.toString()).putString(ACTIVE_KEY, id).commit()) {
            "Android не смог сохранить зашифрованный профиль."
        }
        return SavedVpnProfile(id, safeName, safeProvider, inspected.format.name, createdAt)
    }

    @Synchronized
    fun list(context: Context): List<SavedVpnProfile> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val root = try { JSONObject(prefs.getString(ITEMS_KEY, "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        return root.keys().asSequence().mapNotNull { id ->
            val item = root.optJSONObject(id) ?: return@mapNotNull null
            SavedVpnProfile(
                id = id,
                name = item.optString("name", "VPN профиль"),
                provider = item.optString("provider", "Импорт"),
                format = item.optString("format", "UNKNOWN"),
                createdAt = item.optLong("createdAt", 0L)
            )
        }.sortedByDescending { it.createdAt }.toList()
    }

    @Synchronized
    fun load(context: Context, id: String): String? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val root = try { JSONObject(prefs.getString(ITEMS_KEY, "{}") ?: "{}") } catch (_: Exception) { return null }
        val item = root.optJSONObject(id) ?: return null
        return try {
            val cipher = Cipher.getInstance(CIPHER)
            val iv = Base64.decode(item.getString("iv"), Base64.NO_WRAP)
            val ciphertext = Base64.decode(item.getString("ciphertext"), Base64.NO_WRAP)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: Exception) {
            delete(context, id)
            null
        }
    }

    fun activeId(context: Context): String? =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(ACTIVE_KEY, null)

    fun setActive(context: Context, id: String?) {
        val edit = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (id == null) edit.remove(ACTIVE_KEY) else edit.putString(ACTIVE_KEY, id)
        edit.apply()
    }

    @Synchronized
    fun delete(context: Context, id: String) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val root = try { JSONObject(prefs.getString(ITEMS_KEY, "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        root.remove(id)
        val edit = prefs.edit().putString(ITEMS_KEY, root.toString())
        if (prefs.getString(ACTIVE_KEY, null) == id) edit.remove(ACTIVE_KEY)
        edit.apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
