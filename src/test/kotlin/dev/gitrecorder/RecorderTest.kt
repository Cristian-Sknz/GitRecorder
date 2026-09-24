package dev.gitrecorder

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RecorderTest {
    @TempDir lateinit var temp: Path

    @Test
    fun `imports only published matching commits and stays idempotent`() {
        val sourceRemote = temp.resolve("source.git")
        val destinationRemote = temp.resolve("destination.git")
        val sourcePath = temp.resolve("source")
        val storePath = temp.resolve("store")
        Git().run("init", "--bare", "-b", "main", sourceRemote.toString())
        Git().run("init", "--bare", "-b", "main", destinationRemote.toString())
        Git().run("init", "-b", "main", sourcePath.toString())
        val source = Git(sourcePath)
        source.run("remote", "add", "origin", sourceRemote.toString())
        source.run("config", "commit.gpgsign", "false")
        Files.writeString(sourcePath.resolve("secret.txt"), "source content")
        source.run("add", "secret.txt")
        commit(source, "Published work", "me@example.com")
        source.run("push", "origin", "main")

        val destination = Destination.initialize(storePath, destinationRemote.toString())
        destination.state.addEmail("me@example.com")
        val first = Recorder.sync(storePath, sourcePath, "origin", null, "fixture", null, true)
        assertEquals(1, first.created)
        assertTrue(first.integrated)
        assertEquals(destination.emptyTree, destination.git.run("rev-parse", "${destination.branchTip(first.branch)}^{tree}"))

        Files.writeString(sourcePath.resolve("secret.txt"), "unpublished change")
        source.run("add", "secret.txt")
        commit(source, "Local work", "me@example.com")
        val second = Recorder.sync(storePath, sourcePath, "origin", null, "fixture", null, true)
        assertEquals(0, second.created)
        assertEquals(1, second.selected)
        assertTrue(!second.integrated)

        source.run("checkout", "-b", "android", "origin/main")
        Files.writeString(sourcePath.resolve("android.txt"), "android work")
        source.run("add", "android.txt")
        commit(source, "Android work", "me@example.com")
        source.run("push", "origin", "android")
        val mainBefore = destination.git.run("rev-parse", "main")
        val archive = Recorder.sync(storePath, sourcePath, "origin", "android", "fixture", null, true)
        assertEquals(2, archive.created)
        assertTrue(!archive.integrated)
        assertEquals(mainBefore, destination.git.run("rev-parse", "main"))

        val promoted = Recorder.sync(storePath, sourcePath, "origin", "android", "fixture", null, true, true)
        assertEquals(0, promoted.created)
        assertTrue(promoted.integrated)
        assertEquals("android", Destination.open(storePath).state.get("source.fixture.primaryBranch"))
        assertEquals(0, destination.git.attempt("merge-base", "--is-ancestor",
            destination.branchTip(promoted.branch)!!, "main").code)
        val promotedMain = destination.git.run("rev-parse", "main")
        assertTrue(!Recorder.sync(storePath, sourcePath, "origin", "android", "fixture", null, true).integrated)
        assertEquals(promotedMain, destination.git.run("rev-parse", "main"))
        assertTrue(!Recorder.sync(storePath, sourcePath, "origin", null, "fixture", null, true).integrated)
        assertEquals(promotedMain, destination.git.run("rev-parse", "main"))

        destination.push()
        val secondStore = temp.resolve("second-store")
        val recovered = Destination.initialize(secondStore, destinationRemote.toString())
        assertEquals("android", recovered.state.get("source.fixture.primaryBranch"))
        Files.writeString(sourcePath.resolve("android-next.txt"), "more work")
        source.run("add", "android-next.txt")
        commit(source, "More Android work", "me@example.com")
        source.run("push", "origin", "android")
        val continued = Recorder.sync(secondStore, sourcePath, "origin", "android", "fixture", null, true)
        assertEquals(1, continued.created)
        assertTrue(continued.integrated)
    }

    @Test
    fun `clones a remote temporarily and removes it after success or failure`() {
        val sourceRemote = temp.resolve("source-remote.git")
        val destinationRemote = temp.resolve("destination-remote.git")
        val sourcePath = temp.resolve("working")
        val storePath = temp.resolve("store")
        val scratch = temp.resolve("scratch")
        Files.createDirectory(scratch)
        Git().run("init", "--bare", "-b", "main", sourceRemote.toString())
        Git().run("init", "--bare", "-b", "main", destinationRemote.toString())
        Git().run("init", "-b", "main", sourcePath.toString())
        val source = Git(sourcePath)
        source.run("config", "commit.gpgsign", "false")
        source.run("remote", "add", "origin", sourceRemote.toString())
        Files.writeString(sourcePath.resolve("secret.txt"), "secret")
        source.run("add", "secret.txt")
        commit(source, "Published", "me@example.com")
        source.run("push", "origin", "main")
        Files.writeString(sourcePath.resolve("local.txt"), "unpublished")
        source.run("add", "local.txt")
        commit(source, "Local only", "me@example.com")

        val destination = Destination.initialize(storePath, destinationRemote.toString())
        destination.state.addEmail("me@example.com")
        val first = Recorder.syncRemote(storePath, sourceRemote.toString(), null, null, null, scratch)
        assertEquals(1, first.created)
        assertEquals(1, first.scanned)
        assertTrue(Files.list(scratch).use { it.findAny().isEmpty })
        assertEquals(destination.emptyTree, destination.git.run("rev-parse", "${destination.branchTip(first.branch)}^{tree}"))

        val second = Recorder.syncRemote(storePath, sourceRemote.toString(), null, null, null, scratch)
        assertEquals(0, second.created)
        assertTrue(Files.list(scratch).use { it.findAny().isEmpty })

        assertFailsWith<RecorderException> {
            Recorder.syncRemote(storePath, temp.resolve("missing.git").toString(), null, null, null, scratch)
        }
        assertTrue(Files.list(scratch).use { it.findAny().isEmpty })
    }

    private fun commit(git: Git, title: String, email: String) {
        git.run("commit", "-m", title, environment = mapOf(
            "GIT_AUTHOR_NAME" to "Test Author",
            "GIT_AUTHOR_EMAIL" to email,
            "GIT_AUTHOR_DATE" to "2024-01-01T10:00:00+00:00",
            "GIT_COMMITTER_NAME" to "Test Author",
            "GIT_COMMITTER_EMAIL" to email,
            "GIT_COMMITTER_DATE" to "2024-01-01T10:00:00+00:00",
        ))
    }
}
