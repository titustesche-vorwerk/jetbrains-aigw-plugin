package dev.aigw.sandbox.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import dev.aigw.sandbox.cli.ToolPaths

class AppState : BaseState() {
    var workspaceHost by string("")
    var profile by string("")
    var sbxPath by string("")
    var gitPath by string("")
    var callbackPort by property(8020)
}

@Service(Service.Level.APP)
@State(name = "AigwAppSettings", storages = [Storage("aigw-sandbox.xml")])
class AigwAppSettings : SimplePersistentStateComponent<AppState>(AppState()) {
    fun toolPaths(): ToolPaths {
        val configured = buildMap {
            state.sbxPath?.takeIf { it.isNotBlank() }?.let { put("sbx", it) }
            state.gitPath?.takeIf { it.isNotBlank() }?.let { put("git", it) }
        }
        return ToolPaths(configured, System.getenv("PATH"))
    }

    fun callbackPort(): Int = effectivePort(state.callbackPort)

    companion object {
        fun effectivePort(configured: Int): Int = if (configured in 1024..65535) configured else 8020

        fun getInstance(): AigwAppSettings = ApplicationManager.getApplication().getService(AigwAppSettings::class.java)
    }
}
