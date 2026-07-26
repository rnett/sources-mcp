package dev.rnett.sources.mcp

import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import org.slf4j.LoggerFactory

class SourcesMcpServer {
    private val logger = LoggerFactory.getLogger(SourcesMcpServer::class.java)
    private val env = SourcesMcpEnvironment.fromEnv()
    private val cacheService = DependencyCacheService(env)
    private val analyzer = DependencyAnalyzer(cacheService)
    private val downloader = DependencySourcesDownloader(env)
    private val viewService = SourcesViewService(analyzer, downloader, env)
    private val syncCheckTools = SyncCheckTools(viewService)
    private val cleanupService = ViewCleanupService(env)
    private lateinit var server: Server

    fun start() {
        server = Server(
            serverInfo = Implementation(
                name = "sources-mcp",
                version = "0.1.0",
            ),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = false),
                ),
            ),
        )

        syncCheckTools.registerAll(server)
        cleanupService.start()

        logger.info("Sources MCP server starting on stdio transport")

        val transport = StdioServerTransport(
            inputStream = System.`in`.asSource().buffered(),
            outputStream = System.out.asSink().buffered(),
        )

        runBlocking {
            val session = server.createSession(transport)
            val done = Job()
            session.onClose { done.complete() }
            done.join()
        }
    }

    fun stop() {
        try {
            runBlocking { server.close() }
        } catch (e: Exception) {
            logger.warn("Error closing server", e)
        }
        cleanupService.stop()
    }
}

fun main() {
    val app = SourcesMcpServer()
    Runtime.getRuntime().addShutdownHook(Thread { app.stop() })
    app.start()
}
