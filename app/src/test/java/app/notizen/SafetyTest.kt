package app.notizen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime

class SafetyTest {
    private val root: File = Files.createTempDirectory("vault").toFile().also {
        File(it, "inbox").mkdirs()
        File(it, "inbox/Einkauf.md").writeText("x")
    }

    @Test fun safeFileAcceptsNotesInsideTheVault() {
        assertNotNull(Vault.safeFile(root, "inbox/Einkauf.md"))
        assertNotNull(Vault.safeFile(root, "inbox/../inbox/Einkauf.md"))
    }

    @Test fun safeFileRejectsEverythingElse() {
        assertNull(Vault.safeFile(root, "../outside.md"))
        assertNull(Vault.safeFile(root, "inbox/../../outside.md"))
        assertNull(Vault.safeFile(root, "/etc/hosts"))
        assertNull(Vault.safeFile(root, "inbox/bild.png"))
        assertNull(Vault.safeFile(root, ".git/config.md"))
        assertNull(Vault.safeFile(root, ".obsidian/x.md"))
        assertNull(Vault.safeFile(root, ""))
    }

    @Test fun conflictCopyNameIsRecognised() {
        val copy = Conflicts.copyPath("inbox/Einkauf.md", LocalDateTime.of(2026, 11, 18, 9, 5))
        assertEquals("inbox/Einkauf (Konflikt Handy 2026-11-18 0905).md", copy)
        assertEquals("inbox/Einkauf.md", Conflicts.original(copy))
    }
}
