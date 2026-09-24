package dev.gitrecorder

import java.nio.file.Files
import java.nio.file.Path

internal class Destination private constructor(val path: Path) {
    val git = Git(path)
    val root: String = git.run("rev-list", "--max-parents=0", MAIN_REF).lineSequence().firstOrNull()
        ?: throw RecorderException("Branch main do destino está vazia.")
    val state = State(git, root)
    val emptyTree: String = git.run("mktree", input = "")

    fun branchTip(branch: String): String? {
        val result = git.attempt("rev-parse", "--verify", "refs/heads/$branch")
        if (result.code == 0) return result.stdout
        if (branch.startsWith("sources/")) {
            val remote = git.attempt("rev-parse", "--verify", "refs/remotes/origin/$branch")
            if (remote.code == 0) {
                git.run("update-ref", "refs/heads/$branch", remote.stdout)
                return remote.stdout
            }
        }
        return null
    }

    fun updateBranch(branch: String, newTip: String, oldTip: String?) {
        val args = mutableListOf("update-ref", "refs/heads/$branch", newTip)
        if (oldTip != null) args += oldTip
        git.run(*args.toTypedArray())
    }

    fun commit(parent: String, title: String, body: String, author: SourceCommit): String =
        git.run(
            "commit-tree", emptyTree, "-p", parent,
            input = "$title\n\n$body\n",
            environment = mapOf(
                "GIT_AUTHOR_NAME" to author.author,
                "GIT_AUTHOR_EMAIL" to author.email,
                "GIT_AUTHOR_DATE" to author.authorDate,
                "GIT_COMMITTER_NAME" to author.author,
                "GIT_COMMITTER_EMAIL" to author.email,
                "GIT_COMMITTER_DATE" to author.authorDate,
            ),
        )

    fun mergeIntoMain(branch: String, tip: String): Boolean {
        val oldMain = branchTip("main") ?: throw RecorderException("Branch main ausente.")
        if (git.attempt("merge-base", "--is-ancestor", tip, oldMain).code == 0) return false
        val newMain = git.run(
            "commit-tree", emptyTree, "-p", oldMain, "-p", tip, "-m", "gitrecorder-main: merge $branch",
            environment = botEnvironment(),
        )
        updateBranch("main", newMain, oldMain)
        return true
    }

    fun push(): String {
        val remote = git.run("ls-remote", "origin", "refs/heads/*", NOTES_REF)
        val remoteRefs = remote.lineSequence().filter(String::isNotBlank).associate { line ->
            val pieces = line.split('\t')
            pieces[1] to pieces[0]
        }
        val localRefs = git.run("for-each-ref", "--format=%(refname)", "refs/heads/sources")
            .lineSequence().filter(String::isNotBlank).toList() + MAIN_REF
        for (ref in localRefs + NOTES_REF) {
            val remoteSha = remoteRefs[ref] ?: continue
            val localSha = git.run("rev-parse", "--verify", ref)
            if (remoteSha == localSha) continue
            // Fetch to make the remote tip available for the ancestry check, without replacing local refs.
            git.run("fetch", "--no-tags", "origin", "+$ref:refs/gitrecorder/remote/${encoded(ref)}")
            requireCondition(
                git.attempt("merge-base", "--is-ancestor", remoteSha, localSha).code == 0,
                "O remoto avançou ou divergiu em $ref. Reconecte uma cópia atualizada; nenhum force push será feito.",
            )
        }
        val refspecs = (localRefs + NOTES_REF).distinct().map { "$it:$it" }
        git.run("push", "--atomic", "origin", *refspecs.toTypedArray())
        return "Publicado: ${refspecs.size} refs no destino."
    }

    fun status(): String {
        val branches = git.run("for-each-ref", "--format=%(refname:short)", "refs/heads/sources")
            .lineSequence().filter(String::isNotBlank).toList()
        val remote = git.run("ls-remote", "origin", "refs/heads/*", NOTES_REF).lineSequence()
            .filter(String::isNotBlank).associate {
                val parts = it.split('\t')
                parts[1] to parts[0]
            }
        val localRefs = listOf(MAIN_REF, NOTES_REF) + branches.map { "refs/heads/$it" }
        val pending = localRefs.any { ref -> remote[ref] != git.run("rev-parse", ref) }
        return buildString {
            appendLine("Destino: $path")
            appendLine("E-mails: ${state.emails().sorted().joinToString().ifEmpty { "nenhum" }}")
            appendLine("Branches de origem: ${branches.size}")
            appendLine("Push pendente: ${if (pending) "sim" else "não"}")
        }.trimEnd()
    }

    companion object {
        fun defaultPath(): Path = System.getenv("GITRECORDER_STORE")?.let(Path::of)
            ?: Path.of(System.getProperty("user.home"), ".gitrecorder", "repository")

        fun open(path: Path): Destination {
            requireCondition(Files.isDirectory(path.resolve(".git")), "Destino não inicializado: $path. Execute gitrecorder init.")
            return Destination(path)
        }

        fun initialize(path: Path, remote: String): Destination {
            requireCondition(!Files.exists(path), "O destino local já existe: $path")
            val parent = path.toAbsolutePath().parent
            Files.createDirectories(parent)
            val remoteHeads = Git().run("ls-remote", "--heads", remote)
            if (remoteHeads.isBlank()) {
                Git().run("init", "-b", "main", path.toString())
                val git = Git(path)
                git.run("remote", "add", "origin", remote)
                val emptyTree = git.run("mktree", input = "")
                val root = git.run("commit-tree", emptyTree, "-m", "gitrecorder-main: initialize", environment = botEnvironment())
                git.run("update-ref", MAIN_REF, root)
                State.create(git, root)
            } else {
                val notes = Git().run("ls-remote", remote, NOTES_REF)
                requireCondition(notes.isNotBlank(), "Remoto não é um destino GitRecorder.")
                Git().run("clone", "--no-checkout", remote, path.toString())
                val git = Git(path)
                git.run("fetch", "--no-tags", "origin", "$NOTES_REF:$NOTES_REF")
                git.run("for-each-ref", "--format=%(refname)", "refs/remotes/origin/sources")
                    .lineSequence().filter(String::isNotBlank).forEach { remoteRef ->
                        val localRef = remoteRef.replaceFirst("refs/remotes/origin/", "refs/heads/")
                        git.run("update-ref", localRef, git.run("rev-parse", remoteRef))
                    }
            }
            return open(path)
        }

        private fun botEnvironment(): Map<String, String> = mapOf(
            "GIT_AUTHOR_NAME" to "GitRecorder",
            "GIT_AUTHOR_EMAIL" to "gitrecorder@localhost",
            "GIT_COMMITTER_NAME" to "GitRecorder",
            "GIT_COMMITTER_EMAIL" to "gitrecorder@localhost",
        )
    }
}
