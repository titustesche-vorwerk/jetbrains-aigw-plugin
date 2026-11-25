package dev.aigw.sandbox.cli

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class ToolNotFound(tool: String) :
    RuntimeException("$tool not found. Set its path in Settings | Tools | AIGW Sandbox.")

/** Resolves CLI tools: configured path, then each PATH dir, then common Homebrew locations. */
class ToolPaths(
    private val configured: Map<String, String>,
    private val pathEnv: String?,
    private val exists: (Path) -> Boolean = { Files.isExecutable(it) },
) {
    fun resolve(tool: String): Path? {
        configured[tool]?.takeIf { it.isNotBlank() }?.let { Path.of(it) }?.takeIf(exists)?.let { return it }
        val dirs = pathEnv.orEmpty().split(File.pathSeparator).filter { it.isNotBlank() } + FALLBACK_DIRS
        return dirs.map { Path.of(it).resolve(tool) }.firstOrNull(exists)
    }

    fun require(tool: String): Path = resolve(tool) ?: throw ToolNotFound(tool)

    companion object {
        val FALLBACK_DIRS = listOf("/opt/homebrew/bin", "/usr/local/bin")
    }
}
