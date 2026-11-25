package dev.aigw.sandbox.settings

import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows
import com.intellij.ui.dsl.builder.MutableProperty

class AigwConfigurable(private val project: Project) :
    BoundSearchableConfigurable("AIGW Sandbox", "dev.aigw.sandbox.settings") {

    private fun lines(text: String): MutableList<String> =
        text.lines().map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()

    override fun createPanel(): DialogPanel {
        val app = AigwAppSettings.getInstance().state
        val proj = AigwProjectSettings.getInstance(project).state
        return panel {
            group("Databricks & tools (all projects)") {
                row("Workspace host:") {
                    textField().columns(COLUMNS_LARGE)
                        .bindText({ app.workspaceHost.orEmpty() }, { app.workspaceHost = it.trim() })
                }
                row("Profile:") {
                    textField().columns(COLUMNS_MEDIUM)
                        .bindText({ app.profile.orEmpty() }, { app.profile = it.trim() })
                }
                row("sbx path:") {
                    textField().columns(COLUMNS_LARGE)
                        .bindText({ app.sbxPath.orEmpty() }, { app.sbxPath = it.trim() })
                        .comment("Leave blank to find sbx on PATH")
                }
                row("git path:") {
                    textField().columns(COLUMNS_LARGE)
                        .bindText({ app.gitPath.orEmpty() }, { app.gitPath = it.trim() })
                        .comment("Leave blank to find git on PATH")
                }
                row("Callback port:") {
                    intTextField(1024..65535)
                        .bindIntText({ app.callbackPort }, { app.callbackPort = it })
                }
            }
            group("This project's sandbox") {
                row("Sandbox name:") {
                    textField().columns(COLUMNS_LARGE)
                        .bindText({ proj.sandboxName.orEmpty() }, { proj.sandboxName = it.trim() })
                        .comment("Blank = ug-claude-<project dir name>")
                }
                row("Env files (one per line):") {
                    textArea().rows(3).columns(COLUMNS_LARGE)
                        .bindText(
                            MutableProperty(
                                { AigwProjectSettings.effectiveEnvFiles(proj.envFiles).joinToString("\n") },
                                {
                                    val entered = lines(it)
                                    proj.envFiles =
                                        if (entered == listOf(AigwProjectSettings.DEFAULT_ENV_FILE)) mutableListOf() else entered
                                },
                            ),
                        )
                }
                row("Env args (one per line):") {
                    textArea().rows(4).columns(COLUMNS_LARGE)
                        .bindText(
                            MutableProperty(
                                { proj.envArgs.joinToString("\n") },
                                { proj.envArgs = lines(it) },
                            ),
                        )
                        .comment(
                            "e.g. ugWorkspace=https://&lt;workspace host&gt;. If an env file declares ugWorkspace and it isn't set here, " +
                                "it is filled in from the workspace host above (like sandbox=&lt;name&gt;).",
                        )
                }
            }
        }
    }
}
