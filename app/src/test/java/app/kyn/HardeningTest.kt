package app.kyn

import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.transport.CredentialItem
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.util.FS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class HardeningTest {
    @Test fun tokenGoesOnlyToTheConfiguredHost() {
        val creds = GitSync.HostCredentials(GitSync.hostOf("https://github.com/me/notes.git"), "secret")
        val user = CredentialItem.Username()
        val pass = CredentialItem.Password()
        assertFalse(creds.get(URIish("https://evil.example/x.git"), user, pass))
        assertNull(pass.value)
        assertTrue(creds.get(URIish("https://GitHub.com/me/notes.git"), user, pass))
        assertEquals("secret", String(pass.value))
    }

    @Test fun noHostNoToken() {
        val creds = GitSync.HostCredentials(GitSync.hostOf(""), "secret")
        assertFalse(creds.get(URIish("https://github.com/me/notes.git"), CredentialItem.Password()))
    }

    @Test fun remoteRedirectsAreRemovedFromTheRepoConfig() {
        val f = Files.createTempFile("config", "").toFile()
        f.writeText(
            """
            [remote "origin"]
            	url = https://evil.example/x.git
            	pushurl = https://evil.example/push.git
            [url "https://evil.example/"]
            	insteadOf = https://github.com/
            [http]
            	proxy = http://evil.example:8080
            [http "https://github.com/"]
            	extraHeader = X-Steal: 1
            [user]
            	name = Me
            """.trimIndent(),
        )
        val c = FileBasedConfig(f, FS.DETECTED).apply { load() }
        GitSync.pinRemote(c, "https://github.com/me/notes.git")
        assertEquals("https://github.com/me/notes.git", c.getString("remote", "origin", "url"))
        assertNull(c.getString("remote", "origin", "pushurl"))
        assertTrue(c.getSubsections("url").isEmpty())
        assertNull(c.getString("http", null, "proxy"))
        assertTrue(c.getSubsections("http").isEmpty())
        assertEquals("Me", c.getString("user", null, "name"))
    }

    @Test fun credentialsAreStrippedFromUrls() {
        assertEquals("https://github.com/me/n.git", GitSync.withoutCredentials("https://me:ghp_x@github.com/me/n.git"))
        assertEquals("https://github.com/me/n.git", GitSync.withoutCredentials("https://ghp_x@github.com/me/n.git"))
        assertEquals("https://github.com/me/n.git", GitSync.withoutCredentials("https://github.com/me/n.git"))
        // an @ in the path is not user info
        assertEquals("https://host/a@b", GitSync.withoutCredentials("https://host/a@b"))
    }

    @Test fun sharedFolderStaysBelowDocuments() {
        assertEquals("Vault", Vault.sharedFolder(".", "Vault"))
        assertEquals("Vault", Vault.sharedFolder("../", "Vault"))
        assertEquals("Vault", Vault.sharedFolder("  ", "Vault"))
        assertEquals("Download", Vault.sharedFolder("../Download", "Vault"))
        assertEquals("etc", Vault.sharedFolder("/etc", "Vault"))
        assertEquals("Notes/Phone", Vault.sharedFolder("Notes/Phone", "Vault"))
    }

    @Test fun symlinksAreNeitherReadNorWrittenThrough() {
        val root = Files.createTempDirectory("vault").toFile()
        val outside = Files.createTempFile("secret", ".md").toFile().apply { writeText("secret") }
        File(root, "real.md").writeText("hi")
        Files.createSymbolicLink(File(root, "link.md").toPath(), outside.toPath())
        assertTrue(Vault.isLink(File(root, "link.md")))
        val failed = runCatching { Vault.write(root, "link.md", emptyList(), "overwritten") }.isFailure
        assertTrue(failed)
        assertEquals("secret", outside.readText())
    }

    @Test fun taskLinesSkipCodeBlocks() {
        val body = "- [ ] real ⏰ 2030-01-01 09:00\n```\n- [ ] example ⏰ 2030-01-01 09:00\n```\nend"
        val reminders = Tasks.lines(body).mapNotNull { Reminders.parse("a.md", it, "09:00") }
        assertEquals(listOf("real"), reminders.map { it.text })
    }

    @Test fun tagsAreCountedMostUsedFirst() {
        val notes = listOf(
            Note("a.md", listOf("tags: [x, y]"), "", 0),
            Note("b.md", emptyList(), "#y", 0),
        )
        assertEquals(listOf("y" to 2, "x" to 1), countTags(notes))
    }

}
