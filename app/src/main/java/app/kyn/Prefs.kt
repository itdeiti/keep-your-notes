package app.kyn

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
 * Where the vault lives on the phone.
 * [Shared]: Documents/<folder>, so Obsidian on the phone can open it, but every app with
 * "all files" access can read it too. [Private]: app-private storage, readable only by this app
 * (Android sandbox, encrypted by the device); notes still reach the Mac through GitHub.
 */
enum class Storage { Shared, Private }

data class Prefs(
    val repoUrl: String = "",
    val folder: String = "Vault",
    val name: String = "",
    val email: String = "",
    val defaultTime: String = "09:00",
    val storage: Storage = Storage.Shared,
) {
    companion object {
        private fun sp(ctx: Context) = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)

        /** The debug app ("app.kyn.debug") must never share Documents/Vault with the real app. */
        fun defaultFolder(ctx: Context) = if (ctx.packageName.endsWith(".debug")) "Vault-Debug" else "Vault"

        fun load(ctx: Context): Prefs {
            val p = sp(ctx)
            val d = Prefs()
            return Prefs(
                repoUrl = p.getString("repoUrl", d.repoUrl)!!,
                folder = p.getString("folder", defaultFolder(ctx))!!,
                name = p.getString("name", d.name)!!,
                email = p.getString("email", d.email)!!,
                defaultTime = p.getString("defaultTime", d.defaultTime)!!,
                storage = runCatching { Storage.valueOf(p.getString("storage", d.storage.name)!!) }.getOrDefault(d.storage),
            )
        }

        fun save(ctx: Context, p: Prefs) {
            sp(ctx).edit()
                .putString("repoUrl", p.repoUrl.trim())
                .putString("folder", Vault.sharedFolder(p.folder, defaultFolder(ctx)))
                .putString("name", p.name.trim())
                .putString("email", p.email.trim())
                .putString("defaultTime", p.defaultTime.trim())
                .putString("storage", p.storage.name)
                .commit() // Vault.dir reads it right away (e.g. right after moving the vault)
        }
    }
}

/** GitHub token, encrypted with a key that never leaves the Android Keystore. */
object Secrets {
    private const val ALIAS = "kyn-token"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    private fun sp(ctx: Context) = ctx.getSharedPreferences("secret", Context.MODE_PRIVATE)

    fun put(ctx: Context, token: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val data = c.iv + c.doFinal(token.toByteArray())
        sp(ctx).edit().putString("token", Base64.encodeToString(data, Base64.NO_WRAP)).apply()
    }

    fun get(ctx: Context): String? = runCatching {
        val data = Base64.decode(sp(ctx).getString("token", null) ?: return null, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, 12))
        String(c.doFinal(data, 12, data.size - 12))
    }.getOrNull()
}

/** Last sync result, shown in the list. */
object Status {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("status", Context.MODE_PRIVATE)
    fun set(ctx: Context, s: String) = sp(ctx).edit().putString("s", s).apply()
    fun get(ctx: Context): String = sp(ctx).getString("s", "")!!
    fun now(ctx: Context): String = Dates.time(ctx, java.time.LocalTime.now())
}
