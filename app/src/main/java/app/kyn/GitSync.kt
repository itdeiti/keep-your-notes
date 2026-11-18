package app.kyn

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeResult.MergeStatus
import org.eclipse.jgit.api.ResetCommand.ResetType
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.treewalk.TreeWalk
import java.io.File
import java.time.LocalDateTime

/**
 * The phone's git engine. Every operation runs under one process-wide lock,
 * so the UI, the background worker and notification actions never collide.
 */
object GitSync {
    private val mutex = Mutex()

    fun isCloned(ctx: Context) = File(Vault.dir(ctx), ".git").isDirectory

    private fun creds(ctx: Context) =
        UsernamePasswordCredentialsProvider("x-access-token", Secrets.get(ctx) ?: "")

    private fun ident(ctx: Context): PersonIdent {
        val p = Prefs.load(ctx)
        return PersonIdent(p.name.ifBlank { "KYN (phone)" }, p.email.ifBlank { "phone@kyn.local" })
    }

    private suspend fun <T> locked(block: () -> T): T =
        mutex.withLock { withContext(Dispatchers.IO) { block() } }

    private fun open(ctx: Context): Git {
        val git = Git.open(Vault.dir(ctx))
        val id = ident(ctx)
        git.repository.config.apply {
            setString("user", null, "name", id.name)
            setString("user", null, "email", id.emailAddress)
            save()
        }
        return git
    }

    /** Run [block] while no git operation touches the vault (e.g. moving it). */
    suspend fun <T> exclusive(block: () -> T): T = locked(block)

    suspend fun cloneOrOpen(ctx: Context) = locked {
        val dir = Vault.dir(ctx)
        if (File(dir, ".git").isDirectory) return@locked
        if (dir.list()?.isNotEmpty() == true) error(ctx.getString(R.string.err_folder_not_empty, dir.path))
        dir.mkdirs()
        Git.cloneRepository()
            .setURI(Prefs.load(ctx).repoUrl)
            .setDirectory(dir)
            .setCredentialsProvider(creds(ctx))
            .call().use { git ->
                val repo = git.repository
                repo.config.apply {
                    // Shared storage has no exec bit and no symlinks.
                    setBoolean("core", null, "filemode", false)
                    setBoolean("core", null, "symlinks", false)
                    save()
                }
                // Empty GitHub repo: start on "main" instead of JGit's default "master".
                if (repo.resolve(Constants.HEAD) == null) repo.updateRef(Constants.HEAD).link("refs/heads/main")
            }
        val ignore = File(dir, ".gitignore")
        if (!ignore.exists()) ignore.writeText(".obsidian/workspace*.json\n.trash/\n")
        Unit
    }

    suspend fun commit(ctx: Context, message: String) = locked {
        if (isCloned(ctx)) open(ctx).use { commitAll(it, ident(ctx), message) }
        Unit
    }

    internal fun commitAll(git: Git, id: PersonIdent, message: String) {
        git.add().addFilepattern(".").call()
        git.add().addFilepattern(".").setUpdate(true).call()
        val s = git.status().call()
        if (s.added.isEmpty() && s.changed.isEmpty() && s.removed.isEmpty()) return
        git.commit().setMessage(message).setAuthor(id).setCommitter(id).call()
    }

    /** Commit leftovers (e.g. edits from Obsidian Android), fetch, merge, push. */
    suspend fun sync(ctx: Context) = locked {
        if (!isCloned(ctx)) return@locked
        try {
            val msgs = Messages(
                ctx.getString(R.string.commit_phone_changes), ctx.getString(R.string.commit_merge_server),
                ctx.getString(R.string.commit_merge_conflict, "%s"),
            )
            val copies = open(ctx).use { syncRepo(it, creds(ctx), ident(ctx), msgs) }
            Status.set(
                ctx,
                ctx.getString(R.string.status_synced, Status.now(ctx)) +
                    if (copies.isEmpty()) "" else ctx.getString(R.string.status_conflict),
            )
            Conflicts.notify(ctx, copies)
        } catch (e: Exception) {
            Status.set(ctx, ctx.getString(R.string.status_error, explain(ctx, e), Status.now(ctx)))
            throw e
        }
    }

    /** Commit messages written by a sync; [conflict] has one %s for the paths. In the app's language. */
    data class Messages(
        val changes: String = "Phone: changes",
        val merge: String = "Merge from server",
        val conflict: String = "Merge with conflict: %s",
    )

    /** Returns the conflict copies this sync created (empty normally). */
    internal fun syncRepo(git: Git, creds: CredentialsProvider?, id: PersonIdent, msgs: Messages = Messages()): List<String> {
        val repo = git.repository
        var copies = emptyList<String>()
        commitAll(git, id, msgs.changes)
        git.fetch().setRemote("origin").setCredentialsProvider(creds).call()
        val branch = repo.branch
        val remote = repo.resolve("refs/remotes/origin/$branch")
        val head = repo.resolve(Constants.HEAD)
        if (remote != null && head == null) {
            repo.updateRef("refs/heads/$branch").apply { setNewObjectId(remote); update() }
            git.reset().setMode(ResetType.HARD).call()
        } else if (remote != null) {
            val r = git.merge().include(remote).setMessage(msgs.merge).call()
            when (r.mergeStatus) {
                MergeStatus.CONFLICTING -> copies = resolveConflicts(git, id, r.conflicts.keys, remote, msgs)
                MergeStatus.FAILED, MergeStatus.ABORTED, MergeStatus.CHECKOUT_CONFLICT,
                MergeStatus.NOT_SUPPORTED ->
                    error("Merge failed (${r.mergeStatus}) ${r.failingPaths?.keys ?: ""}")
                else -> Unit
            }
        }
        if (repo.resolve(Constants.HEAD) != null) push(git, creds, branch)
        return copies
    }

    /** Server version stays in place; the phone's version becomes a conflict copy next to it. */
    private fun resolveConflicts(git: Git, id: PersonIdent, paths: Set<String>, theirs: ObjectId, msgs: Messages): List<String> {
        val repo = git.repository
        val copies = mutableListOf<String>()
        val ours = repo.resolve(Constants.HEAD)
        val now = LocalDateTime.now()
        for (p in paths) {
            val o = blob(repo, ours, p)
            val t = blob(repo, theirs, p)
            val f = File(repo.workTree, p)
            if (t != null) f.writeBytes(t) else f.delete()
            if (o != null && !o.contentEquals(t)) {
                val copy = Conflicts.copyPath(p, now)
                File(repo.workTree, copy).apply { parentFile?.mkdirs() }.writeBytes(o)
                copies += copy
            }
        }
        git.add().addFilepattern(".").call()
        git.add().addFilepattern(".").setUpdate(true).call()
        git.commit().setMessage(msgs.conflict.format(paths.joinToString()))
            .setAuthor(id).setCommitter(id).call()
        return copies
    }

    private fun blob(repo: Repository, commit: ObjectId?, path: String): ByteArray? {
        if (commit == null) return null
        RevWalk(repo).use { rw ->
            val tree = rw.parseCommit(commit).tree
            TreeWalk.forPath(repo, path, tree)?.use { tw -> return repo.open(tw.getObjectId(0)).bytes }
        }
        return null
    }

    private fun push(git: Git, creds: CredentialsProvider?, branch: String) {
        val results = git.push().setRemote("origin")
            .setRefSpecs(RefSpec("refs/heads/$branch:refs/heads/$branch"))
            .setCredentialsProvider(creds).call()
        for (res in results) for (u in res.remoteUpdates) {
            if (u.status != RemoteRefUpdate.Status.OK && u.status != RemoteRefUpdate.Status.UP_TO_DATE)
                error("Push rejected: ${u.status} ${u.message ?: ""}")
        }
    }

    /** Short reason for the status line, in the app's language. */
    fun explain(ctx: Context, e: Throwable): String {
        val all = generateSequence(e) { it.cause }.joinToString(" | ") { it.message ?: it.javaClass.simpleName }
        return when {
            Regex("not authorized|Authentication is required|401|403", RegexOption.IGNORE_CASE)
                .containsMatchIn(all) -> ctx.getString(R.string.err_token)
            Regex("UnknownHost|Unable to resolve host|timed out|Network is unreachable", RegexOption.IGNORE_CASE)
                .containsMatchIn(all) -> ctx.getString(R.string.err_offline)
            Regex("not found|404", RegexOption.IGNORE_CASE).containsMatchIn(all) -> ctx.getString(R.string.err_repo_not_found)
            else -> e.message ?: e.javaClass.simpleName
        }
    }
}
