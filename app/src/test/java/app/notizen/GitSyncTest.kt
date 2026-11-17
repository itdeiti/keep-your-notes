package app.notizen

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
        GitSync.syncRepo(phone, null, phoneId)

        assertTrue(read(phone, "inbox/Einkauf.md").startsWith("- [ ] Hafermilch"))
        val copy = File(phone.repository.workTree, "inbox").listFiles()!!.single { "Konflikt Handy" in it.name }
        assertTrue(copy.readText().startsWith("- [ ] Sojamilch"))
        assertTrue(phone.status().call().isClean)

        GitSync.syncRepo(mac, null, macId)
        assertTrue(File(mac.repository.workTree, "inbox/${copy.name}").exists())
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
}
