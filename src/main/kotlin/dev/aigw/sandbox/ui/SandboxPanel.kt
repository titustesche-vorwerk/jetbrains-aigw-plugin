package dev.aigw.sandbox.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.JBPopupMenu
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.VerticalFlowLayout
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.aigw.sandbox.sbx.Sandbox
import dev.aigw.sandbox.sbx.SandboxStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import org.jetbrains.plugins.terminal.TerminalIcons
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * Sandboxes tool window: a toolbar (Refresh, Create) above one card per sandbox. Every control's state comes from
 * [cardState]; every click goes through [AigwNotifications.guard] (write-intent lock, errors become notifications).
 */
class SandboxPanel(private val project: Project, parent: Disposable) : SimpleToolWindowPanel(true, true) {
    private val service = SandboxService.getInstance(project)
    private val column = JPanel(VerticalFlowLayout(VerticalFlowLayout.TOP, 0, JBUI.scale(6), true, false)).apply {
        border = JBUI.Borders.empty(6)
    }
    private val empty = JBLabel("Loading sandboxes…", SwingConstants.CENTER).apply { foreground = UIUtil.getContextHelpForeground() }
    private val cards = LinkedHashMap<String, SandboxCard>()

    init {
        val group = DefaultActionGroup(RefreshAction(), CreateAction())
        val tb = ActionManager.getInstance().createActionToolbar("AigwSandboxes", group, true)
        tb.targetComponent = this
        toolbar = tb.component
        setContent(ScrollPaneFactory.createScrollPane(column, true).apply {
            horizontalScrollBarPolicy = javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        })

        val scope = service.childScope("SandboxPanel")
        Disposer.register(parent) { scope.cancel() }
        scope.launch(Dispatchers.EDT) {
            merge(
                service.sandboxes.map { }, service.loaded.map { }, service.listError.map { }, service.busy.map { },
                service.inFlight.map { }, service.auth.map { }, service.branches.map { }, service.localBranch.map { },
            ).collect { render() }
        }
    }

    private fun render() {
        val list = service.sandboxes.value
        if (list.isEmpty()) {
            cards.clear()
            column.removeAll()
            empty.text = when {
                service.listError.value != null -> "Could not list sandboxes: ${service.listError.value}"
                service.loaded.value -> "No sandboxes. Use + to create the project sandbox."
                else -> "Loading sandboxes…"
            }
            column.add(empty)
        } else {
            if (cards.keys.toList() != list.map { it.name }) {
                cards.clear()
                column.removeAll()
                list.forEach { s -> SandboxCard(s.name).also { cards[s.name] = it; column.add(it) } }
            }
            list.forEach { cards[it.name]?.update(it) }
        }
        column.revalidate()
        column.repaint()
    }

    /** One sandbox: header row (name, status, Start/Stop, Connect, ⋯), sign-in row and git row (project sandboxes). */
    private inner class SandboxCard(private val name: String) : JPanel(BorderLayout()) {
        private var sandbox: Sandbox? = null
        private var updatingCombo = false

        private val title = JBLabel(name)
        private val status = JBLabel()
        private val toggle = iconButton(AllIcons.Actions.Execute)
        private val connect = iconButton(TerminalIcons.OpenTerminal_13x13)
        private val more = iconButton(AllIcons.Actions.More)
        private val signIn = JButton()
        private val branchModel = DefaultComboBoxModel<String>()
        private val branch = ComboBox(branchModel).apply { minimumSize = JBUI.size(60, minimumSize.height) }
        private val refreshBranches = iconButton(AllIcons.Actions.Refresh).apply { toolTipText = "Reload the sandbox's branches" }
        private val pull = JButton("Pull")
        private val push = JButton("Push")
        private val diff = JButton("Diff")
        private val signInRow = row(FlowLayout.LEFT, signIn)
        private val gitRow = JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
            isOpaque = false
            add(branch, BorderLayout.CENTER)
            add(row(FlowLayout.RIGHT, refreshBranches, pull, push, diff), BorderLayout.EAST)
        }

        init {
            isOpaque = true
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.compound(JBUI.Borders.customLine(JBColor.border(), 1), JBUI.Borders.empty(4, 8))

            val names = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                isOpaque = false
                add(title)
                add(Box.createHorizontalStrut(JBUI.scale(8)))
                add(status)
            }
            val header = JPanel(BorderLayout()).apply {
                isOpaque = false
                add(names, BorderLayout.CENTER)
                add(row(FlowLayout.RIGHT, toggle, connect, more), BorderLayout.EAST)
            }
            val body = JPanel(VerticalFlowLayout(VerticalFlowLayout.TOP, 0, JBUI.scale(2), true, false)).apply {
                isOpaque = false
                add(header)
                add(signInRow)
                add(gitRow)
            }
            add(body, BorderLayout.CENTER)

            toggle.addActionListener {
                act("Start/Stop") { s -> if (s.status == SandboxStatus.RUNNING) service.stop(s) else service.start(s) }
            }
            connect.addActionListener { act("Connect") { service.connectAction.perform(project, it) } }
            more.addActionListener {
                val s = sandbox ?: return@addActionListener
                val st = state(s)
                JBPopupMenu().apply {
                    add(JMenuItem(st.reset.text).apply {
                        isEnabled = st.reset.enabled
                        toolTipText = st.reset.tooltip
                        addActionListener { act("Reset") { service.reset(it) } }
                    })
                }.show(more, 0, more.height)
            }
            signIn.addActionListener { act("Sign in") { service.signIn(it) } }
            branch.addActionListener {
                if (updatingCombo) return@addActionListener
                (branch.selectedItem as? String)?.let { b -> service.selectBranch(name, b) }
            }
            refreshBranches.addActionListener { service.refreshBranches(name) }
            pull.addActionListener { act("Pull") { service.pullAction.perform(project, it, selectedBranch()) } }
            push.addActionListener { act("Push") { service.pushAction.perform(project, it, selectedBranch()) } }
            diff.addActionListener { act("Diff") { service.diffAction.perform(project, it, selectedBranch()) } }
        }

        private fun selectedBranch(): String? = service.branches.value[name]?.selected

        private fun act(what: String, block: (Sandbox) -> Unit) {
            val s = sandbox ?: return
            AigwNotifications.guard(project, "$what (${s.name})") { block(s) }
        }

        private fun state(s: Sandbox): CardState = cardState(
            s,
            isProject = service.isProjectSandbox(s),
            lifecycleBusy = s.name in service.busy.value,
            execInFlight = s.name in service.inFlight.value,
            auth = service.auth.value[s.name] ?: AuthState.UNKNOWN,
            branches = service.branches.value[s.name],
            localBranch = service.localBranch.value,
        )

        fun update(s: Sandbox) {
            sandbox = s
            val st = state(s)
            title.font = title.font.deriveFont(if (st.showGitRow) Font.BOLD else Font.PLAIN)
            val running = s.status == SandboxStatus.RUNNING
            val busy = s.name in service.busy.value || s.name in service.inFlight.value
            status.text = "${if (running) "●" else "○"} ${statusText(s.status)}${if (busy) " …" else ""}"
            status.foreground = if (running) RUNNING_COLOR else UIUtil.getContextHelpForeground()

            toggle.icon = if (st.toggleIsStop) AllIcons.Actions.Suspend else AllIcons.Actions.Execute
            toggle.apply(st.toggle)
            connect.apply(st.connect)
            more.toolTipText = "More actions"

            signInRow.isVisible = st.signIn.visible
            signIn.text = st.signIn.text
            signIn.apply(st.signIn)

            gitRow.isVisible = st.showGitRow
            if (st.showGitRow) {
                val bs = service.branches.value[s.name]
                updatingCombo = true
                try {
                    val items = bs?.branches.orEmpty().ifEmpty { listOfNotNull(bs?.selected ?: service.localBranch.value) }
                    if ((0 until branchModel.size).map { branchModel.getElementAt(it) } != items) {
                        branchModel.removeAllElements()
                        items.forEach { branchModel.addElement(it) }
                    }
                    branchModel.selectedItem = bs?.selected ?: items.firstOrNull()
                } finally {
                    updatingCombo = false
                }
                branch.apply(st.branch)
                refreshBranches.isEnabled = running && s.name !in service.busy.value && bs?.loading != true
                pull.apply(st.pull)
                push.apply(st.push)
                diff.apply(st.diff)
            }
        }
    }

    private fun JComponent.apply(c: ControlState) {
        isVisible = c.visible
        isEnabled = c.enabled
        toolTipText = c.tooltip
        if (this is JButton && icon != null) accessibleContext.accessibleName = c.text
    }

    private fun iconButton(icon: Icon) = JButton(icon).apply {
        isBorderPainted = false
        isContentAreaFilled = false
        isFocusPainted = false
        isRolloverEnabled = true
        preferredSize = JBUI.size(26, 26)
    }

    private fun row(align: Int, vararg cs: JComponent) = JPanel(FlowLayout(align, JBUI.scale(2), 0)).apply {
        isOpaque = false
        cs.forEach { add(it) }
    }

    private inner class RefreshAction : DumbAwareAction("Refresh", "Reload sandboxes (sbx ls)", AllIcons.Actions.Refresh) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
        override fun actionPerformed(e: AnActionEvent) = service.refresh()
    }

    private inner class CreateAction :
        DumbAwareAction("Create Project Sandbox", "Create the sandbox for this project (sbx env run)", AllIcons.General.Add) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = service.canCreateProjectSandbox()
        }

        override fun actionPerformed(e: AnActionEvent) = service.start(null)
    }

    private fun statusText(status: SandboxStatus) = when (status) {
        SandboxStatus.RUNNING -> "running"
        SandboxStatus.STOPPED -> "stopped"
        SandboxStatus.OTHER -> "unknown status"
    }

    companion object {
        private val RUNNING_COLOR = JBColor(0x59A869, 0x499C54)
    }
}
