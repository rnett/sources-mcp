package dev.rnett.sources.mcp

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.nio.file.Path

class SyncCheckTools(
    private val viewService: SourcesViewService = SourcesViewService()
) {
    private val logger = LoggerFactory.getLogger(SyncCheckTools::class.java)

    private fun extractProjectRoot(args: JsonObject): Path {
        val content = args["projectRoot"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Error: 'projectRoot' is required and must not be blank.")
        require(content.isNotBlank()) { "Error: 'projectRoot' is required and must not be blank." }
        require(content.length <= 4096) {
            "Error: 'projectRoot' path exceeds maximum length of 4096 characters."
        }
        return Path.of(content).toAbsolutePath().normalize()
    }

    fun registerAll(server: Server) {
        server.addTool(
            name = "sync_dependencies",
            description = "Analyze, download, and materialize dependency sources for a project. " +
                "Runs ORT dependency analysis on the project at 'projectRoot', downloads all dependency " +
                "source jars/tarballs to a content-addressed cache, and creates a view directory " +
                "containing symlinks (junctions on Windows) to every dependency's source tree. " +
                "Returns the 'viewPath' — an absolute directory path to explore with your own tools " +
                "(e.g. rg, fd, tilth) to read and search dependency sources. " +
                "Pass 'fresh=true' to force ORT re-analysis (reuses cached downloads). " +
                "Pass 'redownload=true' to force re-download of all sources. " +
                "Use 'scopes' to filter by ORT scope name (OR logic). " +
                "This call IS the freshness sync point.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("projectRoot") {
                        put("type", "string")
                        put("description", "Absolute path to the project root directory")
                    }
                    putJsonObject("scopes") {
                        put("type", "array")
                        put("description", "Scope names to filter dependencies (OR logic). Omit for all dependencies.")
                        putJsonObject("items") {
                            put("type", "string")
                        }
                    }
                    putJsonObject("fresh") {
                        put("type", "boolean")
                        put("description", "Re-run ORT analysis and reuse CAS downloads. Default: false")
                        put("default", false)
                    }
                    putJsonObject("redownload") {
                        put("type", "boolean")
                        put("description", "Force re-download all sources to new CAS entries. Default: false")
                        put("default", false)
                    }
                },
                required = listOf("projectRoot")
            ),
            toolAnnotations = ToolAnnotations(
                title = null,
                readOnlyHint = false,
                destructiveHint = true,
                idempotentHint = false
            )
        ) { request ->
            val args = request.arguments
                ?: return@addTool CallToolResult(
                    content = listOf(TextContent("Error: No arguments provided.")),
                    isError = true
                )

            val projectRoot: Path
            try {
                projectRoot = extractProjectRoot(args)
            } catch (e: IllegalArgumentException) {
                return@addTool CallToolResult(
                    content = listOf(TextContent("Error: ${e.message}")),
                    isError = true
                )
            }

            val scopes = (args["scopes"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()).toSet()
            val freshArg = args["fresh"]?.jsonPrimitive?.booleanOrNull
            val redownloadArg = args["redownload"]?.jsonPrimitive?.booleanOrNull

            val mode = when {
                (freshArg ?: false) && (redownloadArg ?: false) -> SyncMode.FULL_REFRESH
                freshArg ?: false -> SyncMode.REFRESH_ANALYSIS
                redownloadArg ?: false -> SyncMode.REDOWNLOAD_SOURCES
                else -> SyncMode.CACHED
            }

            try {
                val sessionView = viewService.sync(
                    projectRoot = projectRoot,
                    scopes = scopes,
                    mode = mode
                )

                val manifest = sessionView.manifest
                val output = buildString {
                    appendLine("## Dependencies Synced")
                    appendLine()
                    appendLine("viewPath: ${sessionView.sourcesDir.toAbsolutePath().normalize()}")
                    appendLine("sessionId: ${manifest.sessionId}")
                    appendLine("createdAt: ${manifest.timestamp}")
                    appendLine("depHash: ${manifest.depHash}")
                    appendLine("dependencyCount: ${manifest.dependencies.size}")
                    appendLine("scopes: ${manifest.scopes.ifEmpty { listOf("(all)") }.joinToString(", ")}")
                }

                return@addTool CallToolResult(content = listOf(TextContent(text = output)))
            } catch (e: IllegalArgumentException) {
                return@addTool CallToolResult(
                    content = listOf(TextContent("Error: ${e.message}")),
                    isError = true
                )
            } catch (e: Exception) {
                logger.error("sync_dependencies failed for $projectRoot", e)
                return@addTool CallToolResult(
                    content = listOf(TextContent("Error: Internal error while syncing dependencies. Check server logs for details.")),
                    isError = true
                )
            }
        }

        server.addTool(
            name = "check_dependencies",
            description = "Quickly check whether an existing dependency view is stale or missing. " +
                "Computes a hash of the project's dependency declaration files and compares against " +
                "the stored hash in the most recently synced view. Returns 'fresh: true' when the hash " +
                "matches — meaning the dependency list hasn't changed and the existing view is still valid. " +
                "Returns 'fresh: false' or 'viewExists: false' when 'sync_dependencies' should be called. " +
                "This is a lightweight check (no download or ORT analysis). " +
                "Also returns the view's age, dependency count, and both stored/current hashes.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("projectRoot") {
                        put("type", "string")
                        put("description", "Absolute path to the project root directory")
                    }
                    putJsonObject("scopes") {
                        put("type", "array")
                        put("description", "Scope names to filter dependencies (OR logic). Omit for all dependencies.")
                        putJsonObject("items") {
                            put("type", "string")
                        }
                    }
                },
                required = listOf("projectRoot")
            ),
            toolAnnotations = ToolAnnotations(
                title = null,
                readOnlyHint = true,
                destructiveHint = false,
                idempotentHint = true
            )
        ) { request ->
            val args = request.arguments
                ?: return@addTool CallToolResult(
                    content = listOf(TextContent("Error: No arguments provided.")),
                    isError = true
                )

            val projectRoot: Path
            try {
                projectRoot = extractProjectRoot(args)
            } catch (e: IllegalArgumentException) {
                return@addTool CallToolResult(
                    content = listOf(TextContent("Error: ${e.message}")),
                    isError = true
                )
            }

            try {
                val scopes = (args["scopes"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()).toSet()
                val stalenessCheck = viewService.check(projectRoot)

                val output = buildString {
                    appendLine("## Dependencies Check")
                    appendLine()
                    appendLine("viewExists: ${stalenessCheck.viewExists}")
                    appendLine("fresh: ${stalenessCheck.fresh}")
                    appendLine("age: ${stalenessCheck.age?.toString() ?: "N/A"}")
                    appendLine("depHash: ${stalenessCheck.depHash ?: "N/A"}")
                    appendLine("currentDepHash: ${stalenessCheck.currentDepHash}")
                    appendLine("dependencyCount: ${stalenessCheck.dependencyCount ?: "N/A"}")
                    if (stalenessCheck.failedDependencies.isNotEmpty()) {
                        appendLine()
                        appendLine("failedDependencies:")
                        stalenessCheck.failedDependencies.forEach { appendLine("  - $it") }
                    }
                    if (stalenessCheck.error != null) {
                        appendLine()
                        appendLine("error: ${stalenessCheck.error}")
                    }
                }

                return@addTool CallToolResult(content = listOf(TextContent(text = output)))
            } catch (e: Exception) {
                logger.error("check_dependencies failed for $projectRoot", e)
                return@addTool CallToolResult(
                    content = listOf(TextContent("Error: Internal error while checking dependencies. Check server logs for details.")),
                    isError = true
                )
            }
        }
    }
}
