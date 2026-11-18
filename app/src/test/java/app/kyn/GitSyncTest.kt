package app.kyn

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Phone and Mac against a local bare "GitHub" repo. */
class GitSyncTest {
    @get:Rule val tmp = TemporaryFolder()
    private val phoneId = PersonIdent("Handy", "handy@test")
    private val macId = PersonIdent("Mac", "mac@test")
    private lateinit var mac: Git
    private lateinit var phone: Git

    @Before fun setUp() {
        val remote = tmp.newFolder("remote.git")
        Git.init().setBare(true).setInitialBranch("main").setDirectory(remote).call().close()
        mac = Git.cloneRepository().setURI(remote.toURI().toString()).setDirectory(tmp.newFolder("mac")).call()
        mac.repository.updateRef("HEAD").link("refs/heads/main")
        write(mac, "inbox/Einkauf.md", "- [ ] Milch\n- [ ] Brot\n- [ ] Käse\n")
        GitSync.syncRepo(mac, null, macId)
        phone = Git.cloneRepository().setURI(remote.toURI().toString()).setDirectory(tmp.newFolder("phone")).call()
    }

    private fun write(g: Git, path: String, text: String) =
        File(g.repository.workTree, path).apply { parentFile!!.mkdirs() }.writeText(text)

    private fun read(g: Git, path: String) = File(g.repository.workTree, path).readText()

    @Test fun pushesNewNoteFromPhone() {
        write(phone, "inbox/Idee.md", "Hallo")
        GitSync.syncRepo(phone, null, phoneId)
        GitSync.syncRepo(mac, null, macId)
        assertEquals("Hallo", read(mac, "inbox/Idee.md"))
    }

    @Test fun mergesDifferentLines() {
        write(mac, "inbox/Einkauf.md", "- [x] Milch\n- [ ] Brot\n- [ ] Käse\n")
        GitSync.syncRepo(mac, null, macId)
        write(phone, "inbox/Einkauf.md", "- [ ] Milch\n- [ ] Brot\n- [ ] Käse\n- [ ] Eier\n")
        GitSync.syncRepo(phone, null, phoneId)
        assertEquals("- [x] Milch\n- [ ] Brot\n- [ ] Käse\n- [ ] Eier\n", read(phone, "inbox/Einkauf.md"))
        GitSync.syncRepo(mac, null, macId)
        assertEquals(read(phone, "inbox/Einkauf.md"), read(mac, "inbox/Einkauf.md"))
    }

    @Test fun conflictKeepsBothVersions() {
        write(mac, "inbox/Einkauf.md", "- [ ] Hafermilch\n- [ ] Brot\n- [ ] Käse\n")
        GitSync.syncRepo(mac, null, macId)
        write(phone, "inbox/Einkauf.md", "- [ ] Sojamilch\n- [ ] Brot\n- [ ] Käse\n")
        val copies = GitSync.syncRepo(phone, null, phoneId)

        assertTrue(read(phone, "inbox/Einkauf.md").startsWith("- [ ] Hafermilch"))
        val copy = File(phone.repository.workTree, "inbox").listFiles()!!.single { "Konflikt Handy" in it.name }
        assertTrue(copy.readText().startsWith("- [ ] Sojamilch"))
        assertTrue(phone.status().call().isClean)
        assertEquals(listOf("inbox/${copy.name}"), copies)
        assertEquals("inbox/Einkauf.md", Conflicts.original(copies.single()))

        GitSync.syncRepo(mac, null, macId)
        assertTrue(File(mac.repository.workTree, "inbox/${copy.name}").exists())

        // keep the phone version: copy replaces the original and disappears
        val root = phone.repository.workTree
        assertEquals("inbox/Einkauf.md", Conflicts.keepCopy(root, copies.single()))
        assertTrue(read(phone, "inbox/Einkauf.md").startsWith("- [ ] Sojamilch"))
        assertFalse(copy.exists())
    }

    @Test fun conflictCopyNames() {
        assertTrue(Conflicts.isCopy("inbox/Einkauf (Konflikt Handy 2026-11-17 1432).md"))
        assertEquals("Einkauf.md", Conflicts.original("Einkauf (Konflikt Handy 2026-11-17 1432).md"))
        assertFalse(Conflicts.isCopy("inbox/Einkauf (Konflikt) .md"))
        assertEquals(null, Conflicts.original("inbox/Einkauf.md"))
        assertFalse(Conflicts.discardCopy(File("."), "inbox/Einkauf.md"))
    }

    @Test fun deletedOnMacEditedOnPhone() {
        File(mac.repository.workTree, "inbox/Einkauf.md").delete()
        GitSync.syncRepo(mac, null, macId)
        write(phone, "inbox/Einkauf.md", "- [ ] Milch\n- [ ] Brot\n- [ ] Käse\n- [ ] Eier\n")
        GitSync.syncRepo(phone, null, phoneId)
        assertFalse(File(phone.repository.workTree, "inbox/Einkauf.md").exists())
        val copy = File(phone.repository.workTree, "inbox").listFiles()!!.single { "Konflikt Handy" in it.name }
        assertTrue(copy.readText().contains("Eier"))
    }

    @Test fun deleteOnPhoneIsPushed() {
        File(phone.repository.workTree, "inbox/Einkauf.md").delete()
        GitSync.syncRepo(phone, null, phoneId)
        GitSync.syncRepo(mac, null, macId)
        assertFalse(File(mac.repository.workTree, "inbox/Einkauf.md").exists())
    }

    private fun cloneRemote(target: File) =
        Git.cloneRepository().setURI(File(tmp.root, "remote.git").toURI().toString()).setDirectory(target).call().close()

    @Test fun brokenCloneLeftoverIsReplacedByAFreshClone() {
        val dir = File(tmp.root, "vault")
        File(dir, ".git/objects").mkdirs() // what a failed clone can leave behind
        File(dir, ".gitignore").writeText(".trash/\n")
        assertFalse(GitSync.isRepo(dir))
        assertEquals(0, GitSync.cloneInto(dir, ::cloneRemote))
        assertTrue(GitSync.isRepo(dir))
        assertEquals("- [ ] Milch\n- [ ] Brot\n- [ ] Käse\n", File(dir, "inbox/Einkauf.md").readText())
    }

    @Test fun filesAlreadyInTheFolderAreKeptNextToTheClone() {
        val dir = File(tmp.root, "vault")
        File(dir, ".git").mkdirs()
        File(dir, "inbox").mkdirs()
        File(dir, "inbox/Neu.md").writeText("nur auf dem Handy")
        File(dir, "inbox/Einkauf.md").writeText("- [ ] Eier\n") // differs from the server
        assertEquals(2, GitSync.cloneInto(dir, ::cloneRemote))
        assertTrue(GitSync.isRepo(dir))
        assertEquals("nur auf dem Handy", File(dir, "inbox/Neu.md").readText())
        assertEquals("- [ ] Milch\n- [ ] Brot\n- [ ] Käse\n", File(dir, "inbox/Einkauf.md").readText())
        val copy = File(dir, "inbox").listFiles()!!.single { Conflicts.isCopy("inbox/" + it.name) }
        assertEquals("- [ ] Eier\n", copy.readText())
        assertFalse(File(tmp.root, "vault.clone").exists())
        assertFalse(File(tmp.root, "vault.old").exists())
    }

    // ---------- rewritten / replaced server history ----------

    /** What the Mac does for a fresh history: one orphan commit with the current files, force-pushed. */
    private fun rewriteServerHistory() {
        mac.checkout().setOrphan(true).setName("fresh").call()
        mac.add().addFilepattern(".").call()
        mac.commit().setMessage("chore: start fresh history").setAuthor(macId).setCommitter(macId).call()
        mac.branchDelete().setBranchNames("main").setForce(true).call()
        mac.branchRename().setOldName("fresh").setNewName("main").call()
        mac.push().setRemote("origin").setRefSpecs(org.eclipse.jgit.transport.RefSpec("+refs/heads/main:refs/heads/main")).call()
    }

    private fun serverHead() = Git.open(File(tmp.root, "remote.git")).use { it.repository.resolve("refs/heads/main") }

    @Test fun syncRefusesUnrelatedServerHistory() {
        write(phone, "inbox/Idee.md", "Hallo")
        GitSync.syncRepo(phone, null, phoneId) // phone has old history
        rewriteServerHistory()
        val before = serverHead()
        write(phone, "inbox/Neu.md", "x")
        try { GitSync.syncRepo(phone, null, phoneId); org.junit.Assert.fail() } catch (_: GitSync.UnrelatedHistory) {}
        assertEquals(before, serverHead()) // nothing of the old history was pushed back
    }

    @Test fun localChangesCompareContentNotHistory() {
        GitSync.syncRepo(phone, null, phoneId)
        phone.fetch().call()
        val remote = { phone.repository.resolve("refs/remotes/origin/main") }
        assertEquals(emptyList<String>(), GitSync.differsFrom(phone.repository, remote()))
        rewriteServerHistory() // same files, new history
        phone.fetch().call()
        assertEquals(emptyList<String>(), GitSync.differsFrom(phone.repository, remote()))
        write(phone, "inbox/Neu.md", "nur Handy")
        write(phone, "inbox/Einkauf.md", "- [ ] anders\n")
        assertEquals(listOf("inbox/Einkauf.md", "inbox/Neu.md"), GitSync.differsFrom(phone.repository, remote()).sorted())
    }

    @Test fun recloneReplacesVaultAndHistory() {
        val dir = phone.repository.workTree
        write(phone, "inbox/Alt.md", "alt")
        GitSync.syncRepo(phone, null, phoneId)
        File(dir, "inbox/Alt.md").delete()
        rewriteServerHistory()
        phone.close()
        GitSync.recloneInto(dir, ::cloneRemote)
        Git.open(dir).use { g ->
            assertEquals(serverHead(), g.repository.resolve("HEAD"))
            assertEquals(1, g.log().call().count())
        }
        assertFalse(File(tmp.root, "phone.clone").exists())
        assertFalse(File(tmp.root, "phone.old").exists())
    }

    @Test fun failedRecloneKeepsTheVault() {
        val dir = phone.repository.workTree
        write(phone, "inbox/Lokal.md", "bleibt")
        try { GitSync.recloneInto(dir) { error("offline") }; org.junit.Assert.fail() } catch (_: IllegalStateException) {}
        assertEquals("bleibt", File(dir, "inbox/Lokal.md").readText())
        assertTrue(GitSync.isRepo(dir))
    }
}
