package org.wardriver.pwnpal

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class Vault(context: Context) {
    private val prefs = context.getSharedPreferences("vault", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return ks.getKey("pwnpal", null) as? SecretKey ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("pwnpal", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun save(profile: Profile) {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val data = c.iv + c.doFinal(profile.json().toByteArray())
        prefs.edit().putString("profile", Base64.encodeToString(data, Base64.NO_WRAP)).apply()
    }
    fun load(): Profile {
        val encoded = prefs.getString("profile", null) ?: return Profile()
        val data = Base64.decode(encoded, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0,12))) }
        return Profile.from(String(c.doFinal(data.copyOfRange(12,data.size))))
    }
    fun pin(host: String): String? = prefs.getString("pin:$host", null)
    fun trust(host: String, key: String) { prefs.edit().putString("pin:$host", key).apply() }
    fun forget() { prefs.edit().clear().apply() }
}
