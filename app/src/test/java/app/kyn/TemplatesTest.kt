package app.kyn

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TemplatesTest {
    private val root: File = Files.createTempDirectory("vault").toFile().also {
        File(it, "templates").mkdirs()
        File(it, "templates/Neue Notiz (Vorlagen).md").writeText("---\ncreated: {{date}}\n---\n")
        File(it, "wissen/templates").mkdirs()
        File(it, "wissen/templates/Echte Notiz.md").writeText("x")
        File(it, ".obsidian").mkdirs()
    }

    @Test fun rootTemplatesFolderIsHiddenButNotNestedOnes() {
        val folders = Vault.folders(root)
        assertEquals(false, "templates" in folders)
        assertEquals(true, "wissen/templates" in folders)
        assertEquals(false, folders.any { it.startsWith(".") })
    }
}
