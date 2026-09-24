package dev.gitrecorder

import java.net.URI
import java.nio.file.Path
import java.security.MessageDigest

internal data class SourceCommit(
    val sha: String,
    val parents: List<String>,
    val author: String,
    val email: String,
    val authorDate: String,
    val title: String,
)

internal data class SourceInfo(
    val git: Git,
    val id: String,
    val name: String,
    val branch: String,
    val defaultBranch: String,
    val remoteUrl: String,
    val webUrl: String?,
    val provider: String,
    val commits: List<SourceCommit>,
    val prLinks: Map<String, String>,
) {
    val isDefault: Boolean get() = branch == defaultBranch
    val tip: String get() = commits.lastOrNull()?.sha ?: throw RecorderException("Branch de origem vazia.")

    fun commitLink(sha: String): String? = when (provider) {
        "Azure DevOps", "GitHub" -> "$webUrl/commit/$sha"
        "GitLab" -> "$webUrl/-/commit/$sha"
        else -> null
    }
}

internal object SourceReader {
    fun read(
        directory: Path,
        remote: String,
        branchOverride: String?,
        repoIdOverride: String?,
        webUrlOverride: String?,
        fetch: Boolean,
    ): SourceInfo {
        val git = Git(directory)
        git.run("rev-parse", "--show-toplevel")
        val remoteUrl = git.run("remote", "get-url", remote).trim()
        val defaultBranch = defaultBranch(git, remote, fetch)
        val branch = branchOverride ?: defaultBranch
        requireCondition(git.attempt("check-ref-format", "--branch", branch).code == 0, "Nome de branch inválido: $branch")
        if (fetch) git.run("fetch", "--no-tags", remote, "+refs/heads/$branch:refs/remotes/$remote/$branch")
        val ref = "refs/remotes/$remote/$branch"
        requireCondition(git.attempt("rev-parse", "--verify", ref).code == 0, "Branch publicada não encontrada: $remote/$branch")
        val normalized = normalizeRemote(remoteUrl)
        val id = repoIdOverride?.let {
            requireCondition(Regex("[A-Za-z0-9._-]+").matches(it), "--repo-id deve conter apenas letras, números, ponto, _ ou -.")
            it
        } ?: digest(normalized).take(16)
        val derivedWebUrl = webUrlOverride?.let(::sanitizeWebUrl) ?: deriveWebUrl(normalized)
        val provider = when {
            derivedWebUrl?.contains("dev.azure.com/", ignoreCase = true) == true || derivedWebUrl?.contains("visualstudio.com/", ignoreCase = true) == true -> "Azure DevOps"
            derivedWebUrl?.contains("github.com/", ignoreCase = true) == true -> "GitHub"
            derivedWebUrl?.contains("gitlab", ignoreCase = true) == true -> "GitLab"
            else -> "Git"
        }
        val name = normalized.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').removeSuffix(".git")
        requireCondition(name.isNotBlank(), "Não foi possível identificar o nome do repositório de origem; use --repo-id.")
        val commits = readCommits(git, ref)
        val prLinks = if (provider == "Azure DevOps" && derivedWebUrl != null) readAzurePrLinks(git, commits, derivedWebUrl) else emptyMap()
        return SourceInfo(git, id, name, branch, defaultBranch, normalized, derivedWebUrl, provider, commits, prLinks)
    }

    private fun defaultBranch(git: Git, remote: String, fetch: Boolean): String {
        if (fetch) {
            val response = git.attempt("ls-remote", "--symref", remote, "HEAD")
            requireCondition(response.code == 0, "Não foi possível consultar a branch principal de $remote: ${response.stderr}")
            Regex("ref: refs/heads/([^\\s]+)\\s+HEAD").find(response.stdout)?.let { return it.groupValues[1] }
        }
        val symbolic = git.attempt("symbolic-ref", "--quiet", "refs/remotes/$remote/HEAD")
        if (symbolic.code == 0) return symbolic.stdout.substringAfter("refs/remotes/$remote/")
        val candidates = listOf("main", "master").filter {
            git.attempt("rev-parse", "--verify", "refs/remotes/$remote/$it").code == 0
        }
        requireCondition(candidates.size == 1, "Branch principal não identificada. Configure HEAD remoto ou informe --branch.")
        return candidates.single()
    }

    private fun readCommits(git: Git, ref: String): List<SourceCommit> {
        val format = "%H%x1f%P%x1f%an%x1f%ae%x1f%aI%x1f%s%x1e"
        return git.run("log", "--topo-order", "--reverse", "--format=$format", ref)
            .split('\u001e')
            .mapNotNull { record ->
                val fields = record.trim('\r', '\n').split('\u001f')
                if (fields.size != 6) null else SourceCommit(
                    fields[0], fields[1].split(' ').filter(String::isNotBlank), fields[2],
                    fields[3], fields[4], fields[5],
                )
            }
    }

    private fun readAzurePrLinks(git: Git, commits: List<SourceCommit>, webUrl: String): Map<String, String> {
        val links = mutableMapOf<String, String>()
        val reachable = commits.map(SourceCommit::sha).toHashSet()
        val pattern = Regex("(?i)^Merged PR (\\d+)(?::|\\b)")
        for (commit in commits) {
            val id = pattern.find(commit.title)?.groupValues?.get(1) ?: continue
            val url = "$webUrl/pullrequest/$id"
            links[commit.sha] = url
            if (commit.parents.size < 2) continue
            val introduced = git.run("rev-list", commit.parents[1], "^${commit.parents[0]}")
            for (sha in introduced.lineSequence().filter(String::isNotBlank)) {
                if (sha in reachable) links.putIfAbsent(sha, url)
            }
        }
        return links
    }

    private fun normalizeRemote(remote: String): String {
        val trimmed = remote.trim().trimEnd('/')
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            val uri = URI(trimmed)
            return URI(uri.scheme, null, uri.host, uri.port, uri.path.removeSuffix(".git"), null, null).toString().trimEnd('/')
        }
        return trimmed.removeSuffix(".git")
    }

    private fun deriveWebUrl(remote: String): String? {
        if (remote.startsWith("https://") || remote.startsWith("http://")) return remote
        Regex("^[^@]+@ssh\\.dev\\.azure\\.com:v3/([^/]+)/([^/]+)/(.+)$").matchEntire(remote)?.let {
            val (organization, project, repository) = it.destructured
            return "https://dev.azure.com/$organization/$project/_git/$repository"
        }
        Regex("^[^@]+@([^:]+):(.+)$").matchEntire(remote)?.let {
            val (host, path) = it.destructured
            return "https://$host/$path"
        }
        return null
    }

    private fun sanitizeWebUrl(value: String): String {
        val uri = URI(value.trim())
        requireCondition(uri.scheme == "https" && !uri.host.isNullOrBlank(), "--web-url deve ser uma URL HTTPS.")
        return URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toString().trimEnd('/')
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
