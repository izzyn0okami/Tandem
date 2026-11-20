package app.lawnchair.gamemode

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores the RetroAchievements Web API key encrypted with an Android Keystore AES key. */
internal object RaWebApiKeyStore {
    private const val KEY_ALIAS = "tandem_retroachievements_web_api_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val FILE_NAME = "retroachievements-web-api.enc"

    @Synchronized
    fun get(context: Context): String? {
        val file = credentialFile(context)
        if (!file.isFile) return null
        return runCatching {
            val parts = file.readLines()
            require(parts.size == 2)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)),
            )
            String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrNull()
    }

    @Synchronized
    fun save(context: Context, key: String) {
        require(key.isNotBlank())
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(key.trim().toByteArray(Charsets.UTF_8))
        val file = credentialFile(context)
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeText(
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "\n" +
                Base64.encodeToString(encrypted, Base64.NO_WRAP),
        )
        if (!temporary.renameTo(file)) {
            temporary.copyTo(file, overwrite = true)
            temporary.delete()
        }
    }

    @Synchronized
    fun clear(context: Context) {
        credentialFile(context).delete()
    }

    private fun credentialFile(context: Context): File = File(context.applicationContext.noBackupFilesDir, FILE_NAME)

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }
}
