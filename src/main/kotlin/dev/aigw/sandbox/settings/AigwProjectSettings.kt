package dev.aigw.sandbox.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

class ProjectState : BaseState() {
    var sandboxName by string("")
    var envFiles by list<String>()
    var envArgs by list<String>()
}

@Service(Service.Level.PROJECT)
@State(name = "AigwProjectSettings", storages = [Storage("aigw-sandbox.xml")])
class AigwProjectSettings : SimplePersistentStateComponent<ProjectState>(ProjectState()) {
    fun effectiveSandboxName(projectDirName: String): String =
        effectiveSandboxName(state.sandboxName.orEmpty(), projectDirName)

    fun effectiveEnvFiles(): List<String> = effectiveEnvFiles(state.envFiles)

    companion object {
        const val DEFAULT_ENV_FILE = "sbxenv.yaml"

        fun effectiveEnvFiles(configured: List<String>): List<String> =
            configured.filter { it.isNotBlank() }.ifEmpty { listOf(DEFAULT_ENV_FILE) }

        fun effectiveSandboxName(configured: String, projectDirName: String): String =
            configured.trim().ifEmpty { "ug-claude-$projectDirName" }

        fun getInstance(project: Project): AigwProjectSettings = project.getService(AigwProjectSettings::class.java)
    }
}
