package dev.aigw.sandbox.usage

import dev.aigw.sandbox.cli.CliResult
import dev.aigw.sandbox.ui.stripAnsi

const val USAGE_NOT_AVAILABLE = "usage not available"

private val BORDER = charArrayOf('─', '│', '╭', '╮', '╰', '╯', '═', ' ')

/**
 * One-line summary of `ug usage` output: the first line containing `$`, else the first line that is not only border
 * characters; border characters are trimmed from both ends. Never throws.
 */
fun summarizeUsage(result: CliResult): String {
    if (result.exitCode != 0) return USAGE_NOT_AVAILABLE
    val lines = stripAnsi(result.stdout).lines().map { it.trim().trim(*BORDER).trim() }.filter { it.isNotEmpty() }
    return lines.firstOrNull { '$' in it } ?: lines.firstOrNull() ?: USAGE_NOT_AVAILABLE
}
