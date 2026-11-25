package dev.aigw.sandbox.sbx

import dev.aigw.sandbox.auth.normalizeHost

private val ARGS_HEADER = Regex("""^args:\s*$""")
private val KEY = Regex("""^(\s+)([A-Za-z0-9_.-]+):(\s.*)?$""")

/** Keys directly under the top-level `args:` block of an sbx env YAML (line-based). */
fun declaredArgs(yaml: String): Set<String> {
    val result = linkedSetOf<String>()
    var inBlock = false
    var childIndent = -1
    for (rawLine in yaml.lines()) {
        val line = rawLine.trimEnd('\r')
        if (!inBlock) {
            if (ARGS_HEADER.matches(line)) inBlock = true
            continue
        }
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
        if (!line[0].isWhitespace()) break // next top-level key ends the block
        val m = KEY.matchEntire(line) ?: continue
        val indent = m.groupValues[1].length
        if (childIndent < 0) childIndent = indent
        if (indent == childIndent) result += m.groupValues[2]
    }
    return result
}

/**
 * argv (without the leading `sbx`) for `sbx [--no-input] env <verb> ...`. `--detach` is run-only; `--force` applies to run and rm.
 * Declared args the user didn't set are filled in: `sandbox=<name>`, and `ugWorkspace=https://<host>` from [workspaceHost].
 */
fun envArgv(
    verb: String,
    files: List<String>,
    name: String,
    envArgs: List<String>,
    declared: Set<String>,
    detach: Boolean = false,
    noInput: Boolean = false,
    force: Boolean = false,
    workspaceHost: String? = null,
): List<String> {
    require(verb == "run" || verb == "rm") { "unsupported verb: $verb" }
    val isRun = verb == "run"
    val args = envArgs.map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
    if ("sandbox" in declared && args.none { it.startsWith("sandbox=") }) args += "sandbox=$name"
    val host = workspaceHost?.trim().orEmpty()
    if ("ugWorkspace" in declared && host.isNotEmpty() && args.none { it.startsWith("ugWorkspace=") }) {
        args += "ugWorkspace=" + normalizeHost(host).trimEnd('/')
    }
    return buildList {
        if (noInput) add("--no-input")
        add("env")
        add(verb)
        if (isRun && detach) add("--detach")
        addAll(files.map { it.trim() }.filter { it.isNotEmpty() })
        add("--name"); add(name)
        if (force) add("--force")
        args.forEach { add("--env-arg"); add(it) }
    }
}
