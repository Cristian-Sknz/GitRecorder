package dev.gitrecorder

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.AccessDeniedException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.DosFileAttributeView

internal data class SyncResult(val scanned: Int, val selected: Int, val created: Int, val integrated: Boolean, val branch: String)

internal object Recorder {
    fun syncRemote(
        store: Path,
        url: String,
        branch: String?,
        repoId: String?,
        webUrl: String?,
        tempParent: Path = Path.of(System.getProperty("java.io.tmpdir")),
        asPrimary: Boolean = false,
    ): SyncResult {
        requireCondition(url.isNotBlank(), "Informe a URL do repositório de origem.")
        requireCondition(!Regex("^https?://[^/@]+@", RegexOption.IGNORE_CASE).containsMatchIn(url),
            "Não inclua credenciais na URL; configure a autenticação do Git.")
        val parent = tempParent.toRealPath()
        val temporary = Files.createTempDirectory(parent, "gitrecorder-source-")
        requireCondition(temporary.parent == parent, "Diretório temporário inesperado: $temporary")
        try {
            Git().run("clone", "--no-checkout", "--no-tags", "--no-hardlinks", "--filter=blob:none", url, temporary.toString())
            return sync(store, temporary, "origin", branch, repoId, webUrl, false, asPrimary)
        } finally {
            deleteTemporaryClone(temporary, parent)
        }
    }

    fun sync(
        store: Path,
        sourceDirectory: Path,
        remote: String,
        branch: String?,
        repoId: String?,
        webUrl: String?,
        fetch: Boolean,
        asPrimary: Boolean = false,
    ): SyncResult {
        val destination = Destination.open(store)
        val emails = destination.state.emails()
        requireCondition(emails.isNotEmpty(), "Configure pelo menos um e-mail com gitrecorder email add.")
        val source = SourceReader.read(sourceDirectory, remote, branch, repoId, webUrl, fetch)
        val slug = source.name.lowercase().replace(Regex("[^a-z0-9_-]+"), "-").trim('-').ifBlank { "repo" }
        val destinationBranch = "sources/$slug-${source.id.take(8)}/${source.branch}"
        val prefix = statePrefix(source.id, source.branch)
        val primaryKey = "source." + source.id + ".primaryBranch"
        val previousRemote = destination.state.get("source.${source.id}.remote")
        requireCondition(previousRemote == null || previousRemote == source.remoteUrl, "--repo-id já pertence a outra origem.")
        val cursor = destination.state.get("$prefix.cursor")
        requireCondition(cursor == null || source.commits.any { it.sha == cursor },
            "O histórico publicado de ${source.name}/${source.branch} foi reescrito após $cursor. Sincronização interrompida.")

        val oldTip = destination.branchTip(destinationBranch)
        val reachable = oldTip?.let {
            destination.git.run("rev-list", it).lineSequence().filter(String::isNotBlank).toHashSet()
        } ?: emptySet()
        var tip = oldTip ?: destination.root
        var created = 0
        val selected = source.commits.filter { it.email.lowercase() in emails }
        for (commit in selected) {
            val mapKey = "$prefix.commit.${commit.sha}"
            val existing = destination.state.get(mapKey)
            if (existing != null && existing in reachable) continue
            val subject = "${source.name}-${source.branch}: ${commit.title}"
            val body = buildString {
                appendLine("Source: ${source.provider}")
                appendLine("Source-SHA: ${commit.sha}")
                source.commitLink(commit.sha)?.let { appendLine("Source-Commit: $it") }
                    ?: source.webUrl?.let { appendLine("Source-Repository: $it") }
                source.prLinks[commit.sha]?.let { appendLine("Source-PR: $it") }
            }.trimEnd()
            tip = destination.commit(tip, subject, body, commit)
            destination.state.put(mapKey, tip)
            created++
        }
        destination.state.put("source.${source.id}.remote", source.remoteUrl)
        destination.state.put("source.${source.id}.name", source.name)
        if (asPrimary) destination.state.put(primaryKey, source.branch)
        destination.state.put("$prefix.cursor", source.tip)
        destination.state.put("$prefix.destination", destinationBranch)
        destination.state.save()
        if (created > 0) destination.updateBranch(destinationBranch, tip, oldTip)
        val primaryBranch = destination.state.get(primaryKey) ?: source.defaultBranch
        val integrated = if (source.branch == primaryBranch && (created > 0 || oldTip != null)) {
            destination.mergeIntoMain(destinationBranch, tip)
        } else false
        return SyncResult(source.commits.size, selected.size, created, integrated, destinationBranch)
    }

    private fun deleteTemporaryClone(directory: Path, expectedParent: Path) {
        requireCondition(directory.toAbsolutePath().normalize().parent == expectedParent,
            "Recusa de remover diretório fora da pasta temporária: $directory")
        Files.walkFileTree(directory, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                deleteFile(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
                if (error != null) throw error
                deleteFile(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun deleteFile(path: Path) {
        try {
            Files.delete(path)
        } catch (error: AccessDeniedException) {
            val dos = Files.getFileAttributeView(path, DosFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
            if (dos == null || !dos.readAttributes().isReadOnly) throw error
            dos.setReadOnly(false)
            Files.delete(path)
        }
    }
}
