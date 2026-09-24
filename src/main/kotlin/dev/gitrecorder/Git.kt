package dev.gitrecorder

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.thread

internal class Git(private val directory: Path? = null) {
    fun inDirectory(path: Path) = Git(path)

    fun run(vararg arguments: String, input: String? = null, environment: Map<String, String> = emptyMap()): String =
        execute(arguments.toList(), input, environment).stdout

    fun attempt(vararg arguments: String): Result = execute(arguments.toList(), null, emptyMap(), false)

    private fun execute(
        arguments: List<String>,
        input: String?,
        environment: Map<String, String>,
        check: Boolean = true,
    ): Result {
        val command = mutableListOf("git")
        if (directory != null) command += listOf("-C", directory.toString())
        command += arguments
        val builder = ProcessBuilder(command)
        builder.environment().putAll(environment)
        val process = try {
            builder.start()
        } catch (error: Exception) {
            throw RecorderException("Não foi possível iniciar Git. Instale Git e confira o PATH: ${error.message}")
        }
        val errorBytes = arrayOf(ByteArray(0))
        val errorReader = thread(start = true, isDaemon = true) { errorBytes[0] = process.errorStream.readAllBytes() }
        process.outputStream.use { stream -> if (input != null) stream.write(input.toByteArray(StandardCharsets.UTF_8)) }
        val output = process.inputStream.readAllBytes().toString(StandardCharsets.UTF_8)
        val exitCode = process.waitFor()
        errorReader.join()
        val error = errorBytes[0].toString(StandardCharsets.UTF_8)
        if (check && exitCode != 0) {
            throw RecorderException("Git falhou (${arguments.joinToString(" ")}): ${error.trim().ifBlank { output.trim() }}")
        }
        return Result(exitCode, output.trimEnd('\r', '\n'), error.trim())
    }

    data class Result(val code: Int, val stdout: String, val stderr: String)
}

internal class RecorderException(message: String) : RuntimeException(message)

internal fun requireCondition(condition: Boolean, message: String) {
    if (!condition) throw RecorderException(message)
}

internal fun tempText(text: String): Path {
    val file = Files.createTempFile("gitrecorder-", ".txt")
    Files.writeString(file, text, StandardCharsets.UTF_8)
    return file
}
