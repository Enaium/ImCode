package cn.enaium.imcode.config

import cn.enaium.imcode.platform.Platform
import cn.enaium.imcode.platform.ioFile
import kotlinx.serialization.json.Json

/** Persists [Config] as pretty-printed JSON in `~/.imcode/config.json`. */
object ConfigStore {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val dirPath: String = ioFile(Platform.userHome).absolutePath + if (Platform.isWindows) "\\" else "/" + ".imcode"
    val filePath: String = ioFile(dirPath).absolutePath + if (Platform.isWindows) "\\" else "/" + "config.json"

    fun load(): Config {
        return try {
            val f = ioFile(filePath)
            if (!f.exists) return default()
            migrate(json.decodeFromString(Config.serializer(), f.readText()))
        } catch (e: Exception) {
            default()
        }

    }

    /**
     * kotlin-lsp versions >= 262 default to a socket transport and must be
     * forced onto stdio; upgrade configs that predate this knowledge.
     */
    private fun migrate(cfg: Config): Config {
        val servers = cfg.lspServers.map { server ->
            if (server.command.size == 1 && server.command[0] == "kotlin-lsp" && "--stdio" !in server.command) {
                server.copy(command = listOf("kotlin-lsp", "--stdio"))
            } else server
        }
        return cfg.copy(lspServers = servers)
    }

    fun default(): Config = Config(lspServers = listOf(LspServer(command = listOf("kotlin-lsp", "--stdio"))))

    fun save(config: Config) {
        try {
            val dir = ioFile(dirPath)
            if (!dir.exists) dir.mkdirs()
            ioFile(filePath).writeText(json.encodeToString(Config.serializer(), config))
        } catch (e: Exception) {
            // non-fatal: config persistence failures must not kill the editor
        }
    }
}