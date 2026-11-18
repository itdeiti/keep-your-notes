package app.kyn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64

/** Compatibility with Meld Encrypt files; test data in src/test/resources/meld (see README there). */
class MeldCryptoTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun res(name: String) = javaClass.classLoader!!.getResource("meld/$name")!!.readText()

    private val pass = "korrekt Pferd Batterie 🐎".toCharArray()
    private val note = "# Geheim\n\n- [ ] Arzt anrufen 📅 2026-12-01\nÄrger & \"Quotes\" ✓\n"
    private val section = "Zeile 1\n**fett** Zeile 2"
    private val v3Inline = "AQNbYPSjciY2lp93Ma2oPjMXi3NYH9C344TwrQwft+oH9nVttlWCmHadqw3N6dAOOWIUX86e3VMWqDmjn19l/WcOs3Rpp/Yv"
    private val v3InlineHighIter = "AQST4A6YmDJT5eLlbD4ttO0edAnXVW2RTCu1GOARuXnw6gFdfPVo3NcR1H5K11SXxJa7kwMoOaUak5hR0MwDmgRvY1Q0bqOT"

    private val legacyPass = "legacy-release-password".toCharArray()
    private val legacyText = "Legacy release secret café 🥑"

    @Test fun pbkdf2MatchesJca() {
        // ASCII password: the JCA provider and our UTF-8 implementation must agree
        val salt = ByteArray(16) { it.toByte() }
        val jca = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
            .generateSecret(javax.crypto.spec.PBEKeySpec("secret".toCharArray(), salt, 1000, 256)).encoded
        assertTrue(jca.contentEquals(MeldCrypto.pbkdf2Sha512("secret".toCharArray(), salt, 1000)))
    }

    @Test fun v3WholeNote() {
        val f = MeldCrypto.parseFile(res("v3-whole.mdenc"))
        assertEquals("Pferd?", f.hint)
        assertEquals(note, MeldCrypto.decrypt(f.blob!!, pass))
    }

    @Test fun v2WholeNoteFromPlugin() {
        val f = MeldCrypto.parseFile(res("legacy-whole.mdenc"))
        assertEquals("legacy test", f.hint)
        assertEquals(legacyText, MeldCrypto.decrypt(f.blob!!, legacyPass))
    }

    @Test fun wrongPasswordIsNull() {
        val f = MeldCrypto.parseFile(res("v3-whole.mdenc"))
        assertNull(MeldCrypto.decrypt(f.blob!!, "falsch".toCharArray()))
        assertNull(MeldCrypto.decrypt(f.blob!!, CharArray(0)))
        assertNull(MeldCrypto.decrypt(MeldCrypto.parseFile(res("legacy-whole.mdenc")).blob!!, pass))
    }

    @Test fun v3HeaderIsAuthenticated() {
        // raising the iteration count (still in bounds) changes the AAD: decryption must fail, not ignore it
        val tampered = res("v3-whole.mdenc").replace("\"iterations\": 220000", "\"iterations\": 220001")
        assertNull(MeldCrypto.decrypt(MeldCrypto.parseFile(tampered).blob!!, pass))
        // the hint is not authenticated (as in the plugin): changing it keeps the content readable
        val hint = res("v3-whole.mdenc").replace("Pferd?", "anders")
        assertEquals(note, MeldCrypto.decrypt(MeldCrypto.parseFile(hint).blob!!, pass))
    }

    @Test fun flippedCiphertextFails() {
        val f = MeldCrypto.parseFile(res("v3-whole.mdenc")).blob as MeldCrypto.V3
        val data = f.data.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val bad = MeldCrypto.V3(f.iterations, f.salt, f.iv, data, "x")
        assertNull(MeldCrypto.decrypt(bad, pass))
    }

    @Test fun v3IterationBounds() {
        val low = res("v3-whole.mdenc").replace("\"iterations\": 220000", "\"iterations\": 1000")
        val high = res("v3-whole.mdenc").replace("\"iterations\": 220000", "\"iterations\": 2000001")
        for (t in listOf(low, high)) try { MeldCrypto.parseFile(t); fail() } catch (_: MeldCrypto.Unsupported) {}
    }

    @Test fun oldAndUnknownFormatsAreUnsupported() {
        for (v in listOf("1.0", "9.9")) try {
            MeldCrypto.parseFile("""{"version":"$v","hint":"","encodedData":"AAAA"}"""); fail()
        } catch (_: MeldCrypto.Unsupported) {}
        try { MeldCrypto.parseFile("not json"); fail() } catch (_: MeldCrypto.Unsupported) {}
        val alpha = MeldCrypto.findInline("%%🔐α AAAA 🔐%%").single()
        try { MeldCrypto.blob(alpha); fail() } catch (_: MeldCrypto.Unsupported) {}
    }

    @Test fun emptyFileIsEmptyNote() {
        assertNull(MeldCrypto.parseFile("").blob)
        assertNull(MeldCrypto.parseFile("""{"version":"2.0","hint":"","encodedData":""}""").blob)
    }

    @Test fun inlineV3WithHintAndText() {
        val line = "Vorher %%🔐γ 💡Tipp💡$v3Inline 🔐%% nachher"
        val i = MeldCrypto.findInline(line).single()
        assertEquals("Tipp", i.hint)
        assertEquals('γ', i.version)
        assertEquals("Vorher ", line.substring(0, i.range.first))
        assertEquals(" nachher", line.substring(i.range.last + 1))
        assertEquals(section, MeldCrypto.decrypt(MeldCrypto.blob(i), pass))
    }

    @Test fun inlineV3VisibleAndHighIterations() {
        val i = MeldCrypto.findInline("🔐γ $v3InlineHighIter 🔐").single()
        assertEquals("", i.hint)
        val b = MeldCrypto.blob(i) as MeldCrypto.V3
        assertEquals(300_000, b.iterations)
        assertEquals(section, MeldCrypto.decrypt(b, pass))
    }

    @Test fun inlineV2FromPlugin() {
        val line = res("legacy-inline.md").lines().first { "🔐" in it }
        val i = MeldCrypto.findInline(line).single()
        assertEquals('β', i.version)
        assertEquals(legacyText, MeldCrypto.decrypt(MeldCrypto.blob(i), legacyPass))
    }

    @Test fun visibleSectionAsLastLineOfNote() {
        // shape seen on a real Obsidian note (Meld Encrypt 3.1.1, "show in reading view", hint, last line)
        val line = "🔐γ 💡dev💡AQNbYCSvHgJTkpR6o2KuBvSLMk2MSOoI6jdu1oHiY2Y8sVITvP7FxwfCtyPh1B5zh/jthD+y4HFigFeL 🔐"
        val body = Vault.parse("---\ncreated: 2026-11-18\n---\n# Titel\n\nText\n$line").second
        val i = MeldCrypto.findInline(body.lines().last()).single()
        assertEquals("dev", i.hint)
        assertEquals("twelve chars", MeldCrypto.decrypt(MeldCrypto.blob(i), "dev-pass".toCharArray()))
    }

    @Test fun twoSectionsInOneLine() {
        val line = "a %%🔐γ $v3Inline 🔐%% b 🔐γ 💡x💡$v3InlineHighIter 🔐 c"
        val found = MeldCrypto.findInline(line)
        assertEquals(2, found.size)
        assertEquals(listOf("", "x"), found.map { it.hint })
    }

    @Test fun plainLinesHaveNoSections() {
        assertTrue(MeldCrypto.findInline("- [ ] normal 🔐 emoji").isEmpty())
        assertTrue(MeldCrypto.findInline("nichts").isEmpty())
    }

    @Test fun inlineCiphertextIsCompact() {
        // layout check: [1][iterations][salt 16][iv 12][ct]
        val b = Base64.getDecoder().decode(v3Inline)
        assertEquals(1, b[0].toInt())
    }

    // ---------- vault: encrypted notes are listed by name only ----------

    @Test fun vaultListsEncryptedNoteWithoutContent() {
        val root = tmp.root
        File(root, "privat").mkdirs()
        File(root, "privat/Arzt.mdenc").writeText(res("v3-whole.mdenc"))
        val n = Vault.read(root, File(root, "privat/Arzt.mdenc"))
        assertTrue(n.encrypted)
        assertEquals("Arzt", n.title)
        assertEquals("", n.body)
        assertTrue(n.front.isEmpty())
        // no tasks, tags or search hits from encrypted content
        assertTrue(Tasks.open(listOf(n)).isEmpty())
        assertTrue(n.tags.isEmpty())
        assertTrue(filterNotes(listOf(n), null, "Arzt anrufen").isEmpty())
    }

    @Test fun vaultNeverWritesEncryptedNotes() {
        try { Vault.write(tmp.root, "privat/Arzt.mdenc", emptyList(), "klartext"); fail() } catch (_: IllegalArgumentException) {}
        assertFalse(File(tmp.root, "privat/Arzt.mdenc").exists())
    }

    @Test fun safeFileAllowsEncryptedOnlyWhenAsked() {
        File(tmp.root, "Arzt.mdenc").writeText("{}")
        assertNull(Vault.safeFile(tmp.root, "Arzt.mdenc")) // writers (e.g. ticking a task) never get it
        assertEquals("Arzt.mdenc", Vault.safeFile(tmp.root, "Arzt.mdenc", encrypted = true)?.name)
        assertNull(Vault.safeFile(tmp.root, "../x.mdenc", encrypted = true))
    }

    @Test fun titlesWithoutExtension() {
        assertEquals("Arzt", titleOf("a/Arzt.mdenc"))
        assertEquals("Arzt", titleOf("a/Arzt.encrypted"))
        assertEquals("v1.2", titleOf("v1.2.md"))
        assertEquals("a/Arzt", withoutExt("a/Arzt.mdenc"))
        assertEquals("Arzt", withoutExt("Arzt.md"))
    }

    @Test fun plaintextHidesItself() {
        assertEquals("Plaintext(***)", Plaintext("geheim").toString())
    }
}
