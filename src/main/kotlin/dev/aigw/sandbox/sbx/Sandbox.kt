package dev.aigw.sandbox.sbx

import com.google.gson.JsonParser

enum class SandboxStatus { RUNNING, STOPPED, OTHER }

data class Sandbox(
    val name: String,
    val agent: String,
    val status: SandboxStatus,
    val workspaces: List<String>,
) {
    fun belongsTo(projectPath: String): Boolean {
        val p = projectPath.trimEnd('/')
        return workspaces.any { it.trimEnd('/') == p }
    }
}

fun parseSbxList(json: String): List<Sandbox> {
    val root = JsonParser.parseString(json)
    if (!root.isJsonArray) return emptyList()
    return root.asJsonArray.mapNotNull { el ->
        val o = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        fun str(k: String): String? = o.get(k)?.takeIf { it.isJsonPrimitive }?.asString
        val name = str("name") ?: return@mapNotNull null
        val status = str("status").orEmpty().trim().lowercase().let {
            when {
                it.startsWith("running") -> SandboxStatus.RUNNING
                it == "stopped" -> SandboxStatus.STOPPED
                else -> SandboxStatus.OTHER
            }
        }
        val workspaces = o.get("workspaces")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.filter { it.isJsonPrimitive }?.map { it.asString }.orEmpty()
        Sandbox(name, str("agent").orEmpty(), status, workspaces)
    }
}

fun sortForProject(list: List<Sandbox>, projectPath: String): List<Sandbox> =
    list.sortedWith(compareBy<Sandbox> { !it.belongsTo(projectPath) }.thenBy { it.name })
