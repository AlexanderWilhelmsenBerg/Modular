package io.github.alexanderwilhelmsenberg.modular.addon.proxmox

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

internal class ProxmoxCredentials(context: Context) {
    private val prefs = context.getSharedPreferences("proxmox-connection", Context.MODE_PRIVATE)

    // A profile is explicitly selected in the add-on, and credentials are scoped to its exact HTTPS origin.
    var server: String?
        get() = prefs.getString("server", null)
        set(value) { check(prefs.edit().putString("server", value).commit()) }

    fun tokenId(origin: String): String? = prefs.getString("$origin/id", null)
    fun token(origin: String): String? = prefs.getString("$origin/token", null)?.let { raw ->
        val id = checkNotNull(tokenId(origin))
        "PVEAPIToken=$id=" + decrypt("$origin/token/$id", raw).decodeToString()
    }

    fun setToken(origin: String, id: String, secret: String) {
        val sealed = encrypt("$origin/token/$id", secret.encodeToByteArray())
        check(prefs.edit().putString("$origin/id", id).putString("$origin/token", sealed)
            .putString("server", origin).commit())
    }

    fun forgetToken(origin: String) {
        check(prefs.edit().remove("$origin/id").remove("$origin/token").commit())
    }

    fun hasPin(origin: String) = prefs.contains("$origin/pin")

    fun setPin(origin: String, pin: String) {
        val edit = prefs.edit()
        if (pin.isEmpty()) edit.remove("$origin/pin")
        else {
            val salt = ByteArray(16).also(SecureRandom()::nextBytes)
            edit.putString("$origin/pin", encrypt("$origin/pin", salt + pinHash(pin, salt)))
        }
        check(edit.remove("$origin/pin-until").remove("$origin/pin-failures").commit())
    }

    /** Shared across surfaces and Activity, including between separate attempts to open a shell. */
    fun verifyPin(origin: String, pin: String): String? = synchronized(pinLock) {
        val raw = prefs.getString("$origin/pin", null) ?: return null
        val now = System.currentTimeMillis()
        if (now < prefs.getLong("$origin/pin-until", 0)) return "Too many attempts. Try again in a minute."
        val saved = decrypt("$origin/pin", raw)
        val actual = pinHash(pin, saved.copyOfRange(0, 16))
        val matches = MessageDigest.isEqual(actual, saved.copyOfRange(16, saved.size))
        val failures = if (matches) 0 else (prefs.getInt("$origin/pin-failures", 0) + 1) % 5
        check(prefs.edit().putInt("$origin/pin-failures", failures)
            .putLong("$origin/pin-until", if (!matches && failures == 0) now + 60_000 else 0).commit())
        if (matches) null else "PIN did not match."
    }

    private fun pinHash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 150_000, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }

    private fun encrypt(label: String, data: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(label.encodeToByteArray())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(data), Base64.NO_WRAP)
    }

    private fun decrypt(label: String, data: String): ByteArray {
        val bytes = Base64.decode(data, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
        cipher.updateAAD(label.encodeToByteArray())
        return cipher.doFinal(bytes, 12, bytes.size - 12)
    }

    private fun key(): SecretKey = synchronized(keyLock) {
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keystore.getKey("modular.proxmox.connection", null) as? SecretKey) ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore",
        ).run {
            init(KeyGenParameterSpec.Builder("modular.proxmox.connection",
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }

    companion object {
        private val keyLock = Any()
        private val pinLock = Any()
    }
}
