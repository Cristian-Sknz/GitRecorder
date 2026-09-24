package dev.gitrecorder

import java.io.StringReader
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.Properties

internal const val NOTES_REF = "refs/notes/gitrecorder"
internal const val MAIN_REF = "refs/heads/main"

internal class State(private val git: Git, private val root: String) {
    private val values = Properties()
    private var dirty = false

    init {
        val result = git.attempt("notes", "--ref=$NOTES_REF", "show", root)
        requireCondition(result.code == 0, "O destino não contém metadados GitRecorder em $NOTES_REF.")
        values.load(StringReader(result.stdout))
        requireCondition(values.getProperty("version") == "1", "Versão de metadados GitRecorder incompatível.")
    }

    fun emails(): Set<String> = values.stringPropertyNames()
        .filter { it.startsWith("email.") }
        .map { values.getProperty(it).lowercase() }
        .toSet()

    fun addEmail(address: String) {
        val normalized = address.trim().lowercase()
        requireCondition(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(normalized), "E-mail inválido: $address")
        val emails = emails() + normalized
        replaceEmails(emails)
    }

    fun removeEmail(address: String) = replaceEmails(emails() - address.trim().lowercase())

    private fun replaceEmails(emails: Set<String>) {
        if (this.emails() == emails) return
        values.stringPropertyNames().filter { it.startsWith("email.") }.forEach(values::remove)
        emails.sorted().forEachIndexed { index, email -> values.setProperty("email.$index", email) }
        dirty = true
        save()
    }

    fun get(key: String): String? = values.getProperty(key)
    fun put(key: String, value: String) {
        if (values.getProperty(key) != value) {
            values.setProperty(key, value)
            dirty = true
        }
    }

    fun save() {
        if (!dirty) return
        val writer = StringWriter()
        values.store(writer, null)
        val text = writer.toString()
        val file = tempText(text)
        try {
            git.run("notes", "--ref=$NOTES_REF", "add", "-f", "-F", file.toString(), root,
                environment = botEnvironment())
            dirty = false
        } finally {
            Files.deleteIfExists(file)
        }
    }

    companion object {
        fun create(git: Git, root: String) {
            val file = tempText("version=1\n")
            try {
                git.run("notes", "--ref=$NOTES_REF", "add", "-F", file.toString(), root,
                    environment = botEnvironment())
            } finally {
                Files.deleteIfExists(file)
            }
        }
    }
}

internal fun encoded(value: String): String = Base64.getUrlEncoder().withoutPadding()
    .encodeToString(value.toByteArray(Charsets.UTF_8))

internal fun statePrefix(sourceId: String, branch: String) = "source.$sourceId.${encoded(branch)}"
