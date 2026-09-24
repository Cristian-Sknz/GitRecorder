package dev.gitrecorder

import java.nio.file.Path
import java.util.concurrent.Callable
import kotlin.system.exitProcess
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters

@Command(
    name = "gitrecorder",
    mixinStandardHelpOptions = true,
    version = ["GitRecorder 0.3.0"],
    description = ["Espelha commits publicados como commits vazios no GitHub."],
    subcommands = [InitCommand::class, EmailCommand::class, SyncCommand::class, StatusCommand::class, PushCommand::class],
)
class Main : Runnable {
    override fun run() { CommandLine.usage(this, System.out) }
}

internal class StoreOption {
    @Option(names = ["--store"], description = ["Caminho do repositório local de destino."])
    var path: Path? = null

    fun resolved(): Path = (path ?: Destination.defaultPath()).toAbsolutePath().normalize()
}

@Command(name = "init", mixinStandardHelpOptions = true, description = ["Configura o destino GitHub existente."])
internal class InitCommand : Callable<Int> {
    @CommandLine.Mixin var store = StoreOption()
    @Option(names = ["--remote"], required = true) lateinit var remote: String
    override fun call(): Int {
        val destination = Destination.initialize(store.resolved(), remote)
        println("Destino pronto: ${destination.path}")
        println("Configure e-mails com: gitrecorder email add seu@email.com")
        return 0
    }
}

@Command(name = "email", mixinStandardHelpOptions = true, description = ["Configura os e-mails de autoria aceitos."])
internal class EmailCommand : Callable<Int> {
    @CommandLine.Mixin var store = StoreOption()
    @Parameters(index = "0", description = ["add, remove ou list"]) lateinit var action: String
    @Parameters(index = "1", arity = "0..1") var address: String? = null
    override fun call(): Int {
        val state = Destination.open(store.resolved()).state
        when (action) {
            "add" -> state.addEmail(address ?: throw RecorderException("Informe o e-mail."))
            "remove" -> state.removeEmail(address ?: throw RecorderException("Informe o e-mail."))
            "list" -> state.emails().sorted().forEach(::println)
            else -> throw RecorderException("Ação desconhecida: $action")
        }
        return 0
    }
}

@Command(name = "sync", mixinStandardHelpOptions = true, description = ["Espelha commits publicados da origem atual ou de uma URL."])
internal class SyncCommand : Callable<Int> {
    @CommandLine.Mixin var store = StoreOption()
    @Parameters(index = "0", arity = "0..1", description = ["URL ou caminho de um repositório Git remoto."])
    var sourceUrl: String? = null
    @Option(names = ["--url"], description = ["URL de um repositório Git remoto."])
    var urlOption: String? = null
    @Option(names = ["--branch"]) var branch: String? = null
    @Option(names = ["--as-primary", "--primary"], description = ["Usa esta branch como principal deste repositório no GitRecorder."])
    var asPrimary: Boolean = false
    @Option(names = ["--remote"], defaultValue = "origin") lateinit var remote: String
    @Option(names = ["--repo-id"]) var repoId: String? = null
    @Option(names = ["--web-url"]) var webUrl: String? = null
    @Option(names = ["--no-fetch"], description = ["Usa refs remotas locais, para testes offline."])
    var noFetch: Boolean = false
    override fun call(): Int {
        requireCondition(sourceUrl == null || urlOption == null, "Informe a URL como argumento ou com --url, não ambos.")
        val url = sourceUrl ?: urlOption
        val result = if (url != null) {
            requireCondition(remote == "origin", "--remote só se aplica à sincronização de uma pasta local.")
            requireCondition(!noFetch, "--no-fetch não se aplica à sincronização por URL.")
            Recorder.syncRemote(store.resolved(), url, branch, repoId, webUrl, asPrimary = asPrimary)
        } else {
            Recorder.sync(store.resolved(), Path.of(".").toAbsolutePath(), remote, branch, repoId, webUrl, !noFetch, asPrimary)
        }
        println("Origem: ${result.scanned} commits; ${result.selected} autores selecionados.")
        println("Criados: ${result.created} em ${result.branch}; integrada à main: ${if (result.integrated) "sim" else "não"}.")
        return 0
    }
}

@Command(name = "status", mixinStandardHelpOptions = true, description = ["Mostra o estado do destino."])
internal class StatusCommand : Callable<Int> {
    @CommandLine.Mixin var store = StoreOption()
    override fun call(): Int { println(Destination.open(store.resolved()).status()); return 0 }
}

@Command(name = "push", mixinStandardHelpOptions = true, description = ["Publica branches e Git notes."])
internal class PushCommand : Callable<Int> {
    @CommandLine.Mixin var store = StoreOption()
    override fun call(): Int { println(Destination.open(store.resolved()).push()); return 0 }
}

fun main(args: Array<String>) {
    val cli = CommandLine(Main())
    cli.executionExceptionHandler = CommandLine.IExecutionExceptionHandler { error, _, _ ->
        System.err.println("Erro: ${error.message}")
        1
    }
    exitProcess(cli.execute(*args))
}
