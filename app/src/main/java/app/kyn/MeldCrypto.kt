package app.kyn

import org.json.JSONObject
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Read-only support for notes encrypted with the Obsidian plugin "Meld Encrypt" (meld-cp/obsidian-encrypt).
 *
 * Supported (what the plugin writes since 2023):
 * - v3 ("3.0", inline 🔐γ): PBKDF2-HMAC-SHA512 (220k–2M iterations), AES-256-GCM, 12-byte IV, header as AAD
 * - v2 ("2.0", inline 🔐β): PBKDF2-HMAC-SHA512 210k, AES-256-GCM, 16-byte IV, no AAD
 * Older formats (inline 🔐α and the first one, file "1.0") are reported as [Unsupported]:
 * open the note once in Obsidian, which rewrites it in the current format.
 *
 * kyn writes only v3 (like the plugin since 3.0): [encryptV3] with [fileJson] / [inlineText].
 * Decrypted text exists only in memory, see [SecretSession].
 */
object MeldCrypto {
    /** File extensions of whole encrypted notes. */
    val EXTENSIONS = setOf("mdenc", "encrypted")

    fun isEncryptedFile(name: String) = name.substringAfterLast('.', "").lowercase() in EXTENSIONS

    /** Something that can be decrypted with a password; [key] identifies it for caching. */
    sealed class Blob {
        abstract val key: String
    }

    class V2(val iv: ByteArray, val salt: ByteArray, val data: ByteArray, override val key: String) : Blob()
    class V3(val iterations: Int, val salt: ByteArray, val iv: ByteArray, val data: ByteArray, override val key: String) : Blob()

    /** Format kyn cannot read (too old or unknown); [version] for the message. */
    class Unsupported(val version: String) : Exception("Unsupported Meld Encrypt format $version")

    /** Contents of a .mdenc file. The hint is not authenticated: show it, never trust it. */
    class FileData(val hint: String, val blob: Blob?) // blob null = empty file / empty note

    /**
     * One encrypted section inside a note line: `%%🔐γ 💡hint💡base64 🔐%%` (or without %%).
     * [raw] is the matched text; [hidden] = written with %% (hidden in Obsidian's reading view).
     */
    class Inline(val range: IntRange, val hint: String, val version: Char?, val base64: String, val raw: String, val hidden: Boolean)

    private const val V2_ITER = 210_000
    const val V3_MIN_ITER = 220_000
    const val V3_MAX_ITER = 2_000_000

    // ---------- parsing ----------

    fun parseFile(text: String): FileData {
        if (text.isEmpty()) return FileData("", null)
        val o = try { JSONObject(text) } catch (e: Exception) { throw Unsupported("?") }
        val version = o.optString("version", "?")
        val hint = o.optString("hint", "")
        val encoded = o.optString("encodedData", "")
        return when (version) {
            "3.0" -> FileData(hint, v3FromFile(o))
            "2.0" -> FileData(hint, if (encoded.isEmpty()) null else v2(encoded))
            else -> throw Unsupported(version)
        }
    }

    private fun v3FromFile(o: JSONObject): V3 {
        val kdf = o.optJSONObject("kdf") ?: throw Unsupported("3.0")
        val cipher = o.optJSONObject("cipher") ?: throw Unsupported("3.0")
        if (kdf.optString("name") != "PBKDF2" || kdf.optString("hash") != "SHA-512" ||
            cipher.optString("name") != "AES-GCM" || cipher.optInt("keyLength") != 256 || cipher.optInt("tagLength") != 128
        ) throw Unsupported("3.0")
        val iter = kdf.optInt("iterations", -1)
        val salt = b64(kdf.optString("salt"))
        val iv = b64(cipher.optString("iv"))
        val data = b64(o.optString("encodedData"))
        return v3(iter, salt, iv, data)
    }

    private fun v3(iter: Int, salt: ByteArray, iv: ByteArray, data: ByteArray): V3 {
        if (iter !in V3_MIN_ITER..V3_MAX_ITER || salt.size != 16 || iv.size != 12 || data.size < 16) throw Unsupported("3.0")
        return V3(iter, salt, iv, data, "3:" + enc(salt) + enc(iv) + enc(data))
    }

    private fun v2(encoded: String): V2 {
        val b = b64(encoded)
        if (b.size < 32 + 16) throw Unsupported("2.0")
        return V2(b.copyOfRange(0, 16), b.copyOfRange(16, 32), b.copyOfRange(32, b.size), "2:$encoded")
    }

    /** Compact inline v3: [1][iterations, 3 bytes][salt 16][iv 12][ciphertext+tag]. */
    private fun v3Inline(encoded: String): V3 {
        val b = b64(encoded)
        if (b.size < 48 || b[0].toInt() != 1) throw Unsupported("γ")
        val iter = (b[1].toInt() and 0xff shl 16) or (b[2].toInt() and 0xff shl 8) or (b[3].toInt() and 0xff)
        return v3(iter, b.copyOfRange(4, 20), b.copyOfRange(20, 32), b.copyOfRange(32, b.size))
    }

    /** Blob of an inline section, or [Unsupported] for α / the first format. */
    fun blob(i: Inline): Blob = when (i.version) {
        'γ' -> v3Inline(i.base64)
        'β' -> v2(i.base64)
        else -> throw Unsupported(i.version?.toString() ?: "0")
    }

    private val INLINE = Regex("""(?:%%)?🔐([αβγ])? (?:💡(.*?)💡)?([A-Za-z0-9+/]+={0,2}) 🔐(?:%%)?""")

    /** Encrypted sections in [line] (ciphertext is Base64, so a section never spans lines). */
    fun findInline(line: String): List<Inline> {
        if ("🔐" !in line) return emptyList()
        return INLINE.findAll(line).map { m ->
            Inline(m.range, m.groupValues[2], m.groupValues[1].firstOrNull(), m.groupValues[3], m.value, m.value.startsWith("%%"))
        }.toList()
    }

    // ---------- encryption (v3 only) ----------

    const val V3_DEFAULT_ITER = 220_000
    private val random = java.security.SecureRandom()

    fun encryptV3(text: String, password: CharArray, iterations: Int = V3_DEFAULT_ITER): V3 {
        require(password.isNotEmpty()) { "empty password" }
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val header = V3(iterations, salt, iv, ByteArray(16), "")
        val raw = pbkdf2Sha512(password, salt, iterations)
        try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(raw, "AES"), GCMParameterSpec(128, iv))
            c.updateAAD(aadV3(header))
            return v3(iterations, salt, iv, c.doFinal(text.toByteArray(Charsets.UTF_8)))
        } finally {
            raw.fill(0)
        }
    }

    /** .mdenc content exactly as the plugin writes it: `JSON.stringify(FileData, null, 2)`. */
    fun fileJson(hint: String, b: V3): String = """
        |{
        |  "version": "3.0",
        |  "hint": ${jsonString(hint)},
        |  "encodedData": "${enc(b.data)}",
        |  "kdf": {
        |    "name": "PBKDF2",
        |    "hash": "SHA-512",
        |    "iterations": ${b.iterations},
        |    "salt": "${enc(b.salt)}"
        |  },
        |  "cipher": {
        |    "name": "AES-GCM",
        |    "keyLength": 256,
        |    "iv": "${enc(b.iv)}",
        |    "tagLength": 128
        |  }
        |}""".trimMargin()

    /** Compact inline v3 Base64: [1][iterations, 3 bytes][salt 16][iv 12][ciphertext+tag]. */
    fun encodeInline(b: V3): String {
        val out = ByteArray(32 + b.data.size)
        out[0] = 1
        out[1] = (b.iterations ushr 16).toByte(); out[2] = (b.iterations ushr 8).toByte(); out[3] = b.iterations.toByte()
        b.salt.copyInto(out, 4); b.iv.copyInto(out, 20); b.data.copyInto(out, 32)
        return enc(out)
    }

    /** Inline section as the plugin writes it (γ markers, optional hint), [hidden] = with %%. */
    fun inlineText(b: V3, hint: String, hidden: Boolean): String {
        val h = if (hint.isNotEmpty()) "💡$hint💡" else ""
        return if (hidden) "%%🔐γ $h${encodeInline(b)} 🔐%%" else "🔐γ $h${encodeInline(b)} 🔐"
    }

    /** JSON string literal like JSON.stringify (no escaping of '/' or non-ASCII). */
    private fun jsonString(s: String): String = buildString {
        append('"')
        for (ch in s) when {
            ch == '"' -> append("\\\"")
            ch == '\\' -> append("\\\\")
            ch == '\n' -> append("\\n")
            ch == '\r' -> append("\\r")
            ch == '\t' -> append("\\t")
            ch == '\b' -> append("\\b")
            ch == '\u000C' -> append("\\f")
            ch < ' ' -> append("\\u%04x".format(ch.code))
            else -> append(ch)
        }
        append('"')
    }

    // ---------- decryption ----------

    /** Plaintext, or null if the password is wrong or the data was changed. */
    fun decrypt(blob: Blob, password: CharArray): String? = try {
        when (blob) {
            is V3 -> gcm(password, blob.salt, blob.iterations, blob.iv, blob.data, aadV3(blob))
            is V2 -> gcm(password, blob.salt, V2_ITER, blob.iv, blob.data, null)
        }
    } catch (_: AEADBadTagException) {
        null
    }

    private fun gcm(password: CharArray, salt: ByteArray, iter: Int, iv: ByteArray, data: ByteArray, aad: ByteArray?): String {
        val raw = pbkdf2Sha512(password, salt, iter)
        try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(raw, "AES"), GCMParameterSpec(128, iv))
            if (aad != null) c.updateAAD(aad)
            return String(c.doFinal(data), Charsets.UTF_8)
        } finally {
            raw.fill(0)
        }
    }

    /**
     * 32-byte PBKDF2-HMAC-SHA512 key from the UTF-8 bytes of [password], like WebCrypto in the plugin.
     * Done by hand: how a JCA provider turns chars into bytes differs between providers (Android: BouncyCastle).
     */
    internal fun pbkdf2Sha512(password: CharArray, salt: ByteArray, iter: Int): ByteArray {
        val bb = Charsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(password))
        val pw = ByteArray(bb.remaining()).also { bb.get(it) }
        bb.clear(); while (bb.hasRemaining()) bb.put(0)
        if (pw.isEmpty()) return ByteArray(32) // empty password: wrong key, decryption fails cleanly
        try {
            val mac = Mac.getInstance("HmacSHA512")
            mac.init(SecretKeySpec(pw, "HmacSHA512"))
            mac.update(salt)
            mac.update(byteArrayOf(0, 0, 0, 1)) // block 1 is enough: 64 bytes > 32 needed
            var u = mac.doFinal()
            val t = u.copyOf()
            repeat(iter - 1) {
                u = mac.doFinal(u)
                for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
            }
            return t.copyOf(32).also { t.fill(0); u.fill(0) }
        } finally {
            pw.fill(0)
        }
    }

    /** Same bytes as the plugin's `JSON.stringify({version, kdf, cipher})` (Base64 needs no escaping). */
    internal fun aadV3(b: V3): ByteArray =
        ("""{"version":"3.0","kdf":{"name":"PBKDF2","hash":"SHA-512","iterations":${b.iterations},"salt":"${enc(b.salt)}"},""" +
            """"cipher":{"name":"AES-GCM","keyLength":256,"iv":"${enc(b.iv)}","tagLength":128}}""").toByteArray(Charsets.UTF_8)

    private fun b64(s: String): ByteArray = try { Base64.getDecoder().decode(s) } catch (_: IllegalArgumentException) { throw Unsupported("?") }
    private fun enc(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
}
