package se.seedba.jetbrains.toolwindow

import com.google.gson.JsonObject
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.PopupHandler
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import se.seedba.jetbrains.api.SeedbaseApi
import se.seedba.jetbrains.api.str
import se.seedba.jetbrains.auth.TokenStore
import java.awt.BorderLayout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

class SeedbaseToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = SeedbasePanel(project)
        val content = ContentFactory.getInstance().createContent(panel.component, "", false)
        toolWindow.contentManager.addContent(content)
    }
}

private data class ProjectNode(val data: JsonObject) {
    val id: String get() = data.str("id")
    val name: String get() = data.str("name").ifEmpty { id }
}

private data class GenerationNode(val data: JsonObject, val projectId: String) {
    val id: String get() = data.str("id")
    val status: String get() = data.str("status").ifEmpty { "unknown" }
    val label: String get() = data.str("name").ifEmpty { id.take(8) }
}

private object LoggedOutNode {
    override fun toString(): String = "Not logged in — use the key icon to sign in"
}

private object EmptyGenerationsNode {
    override fun toString(): String = "No generations yet — generate data first"
}

private fun notifyUser(project: Project?, message: String, type: NotificationType) {
    NotificationGroupManager.getInstance()
        .getNotificationGroup("SeedBase")
        .createNotification(message, type)
        .notify(project)
}

class SeedbasePanel(private val ideProject: Project) {
    private val root = DefaultMutableTreeNode()
    private val model = DefaultTreeModel(root)
    private val tree = Tree(model)
    val component: JComponent

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.cellRenderer = object : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(
                tree: JTree,
                value: Any?,
                selected: Boolean,
                expanded: Boolean,
                leaf: Boolean,
                row: Int,
                hasFocus: Boolean,
            ) {
                val node = (value as? DefaultMutableTreeNode)?.userObject
                when (node) {
                    is ProjectNode -> {
                        icon = AllIcons.Nodes.Module
                        append(node.name)
                        val id = node.id
                        if (id.isNotEmpty()) {
                            append("  ${id.take(8)}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        }
                    }
                    is GenerationNode -> {
                        icon = when (node.status) {
                            "completed" -> AllIcons.RunConfigurations.TestPassed
                            "failed" -> AllIcons.RunConfigurations.TestError
                            "cancelled" -> AllIcons.RunConfigurations.TestIgnored
                            "pending", "running" -> AllIcons.Actions.Refresh
                            else -> AllIcons.RunConfigurations.TestNotRan
                        }
                        append("${node.label} · ${node.status}")
                        val created = formatDate(node.data.str("created_at").ifEmpty { node.data.str("created") })
                        if (created.isNotEmpty()) {
                            append("  $created", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        }
                    }
                    else -> {
                        icon = AllIcons.General.Information
                        append(node?.toString() ?: "")
                    }
                }
            }
        }

        val group = DefaultActionGroup().apply {
            add(LoginAction())
            add(LogoutAction())
            addSeparator()
            add(RefreshAction())
            add(GenerateAction())
            add(PushSchemaAction())
            add(OpenInBrowserAction())
            addSeparator()
            add(PullAction("SQL", "sql", "sql"))
            add(PullAction("CSV", "csv", "csv"))
            add(PullAction("JSON", "json", "json"))
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("SeedbaseToolbar", group, true)
        val panel = JPanel(BorderLayout())
        toolbar.targetComponent = panel
        panel.add(toolbar.component, BorderLayout.NORTH)
        panel.add(JBScrollPane(tree), BorderLayout.CENTER)
        component = panel

        PopupHandler.installPopupMenu(tree, group, "SeedbasePopup")

        refresh()
    }

    private fun formatDate(raw: String): String {
        if (raw.isEmpty()) return ""
        return try {
            OffsetDateTime.parse(raw)
                .atZoneSameInstant(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT))
        } catch (_: Exception) {
            ""
        }
    }

    private fun selectedProjectNode(): ProjectNode? {
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return null
        return when (val obj = node.userObject) {
            is ProjectNode -> obj
            is GenerationNode -> {
                val parent = node.parent as? DefaultMutableTreeNode
                parent?.userObject as? ProjectNode
            }
            else -> null
        }
    }

    private fun selectedGenerationNode(): GenerationNode? {
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return null
        return node.userObject as? GenerationNode
    }

    fun refresh() {
        val token = TokenStore.get()
        if (token == null) {
            setNodes(listOf(DefaultMutableTreeNode(LoggedOutNode)))
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val projects = SeedbaseApi.listProjects(token)
                val nodes = projects.map { project ->
                    val projectNode = DefaultMutableTreeNode(ProjectNode(project))
                    val projectId = project.str("id")
                    val generations = SeedbaseApi.listGenerations(token, projectId)
                    if (generations.isEmpty()) {
                        projectNode.add(DefaultMutableTreeNode(EmptyGenerationsNode))
                    } else {
                        for (generation in generations) {
                            projectNode.add(DefaultMutableTreeNode(GenerationNode(generation, projectId)))
                        }
                    }
                    projectNode
                }
                setNodes(nodes)
            } catch (exc: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    notifyUser(ideProject, "SeedBase: ${exc.message}", NotificationType.ERROR)
                }
            }
        }
    }

    private fun setNodes(nodes: List<DefaultMutableTreeNode>) {
        ApplicationManager.getApplication().invokeLater {
            root.removeAllChildren()
            nodes.forEach(root::add)
            model.reload()
            if (nodes.size <= 5) {
                for (i in 0 until tree.rowCount) {
                    tree.expandRow(i)
                }
            }
        }
    }

    private inner class LoginAction : AnAction("Login", "Sign in to SeedBase", AllIcons.General.User) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = TokenStore.get() == null
        }

        override fun actionPerformed(e: AnActionEvent) {
            val init = try {
                SeedbaseApi.initiateLogin()
            } catch (exc: Exception) {
                notifyUser(ideProject, "SeedBase login failed: ${exc.message}", NotificationType.ERROR)
                return
            }
            if (init.browserUrl.isNotEmpty()) {
                BrowserUtil.browse(init.browserUrl)
            }
            object : Task.Backgroundable(ideProject, "SeedBase: waiting for authorization (code ${init.code})…", true) {
                override fun run(indicator: ProgressIndicator) {
                    val token = SeedbaseApi.pollForToken(init.pollUrl) { indicator.checkCanceled() }
                    TokenStore.set(token)
                }

                override fun onSuccess() {
                    notifyUser(ideProject, "SeedBase: logged in.", NotificationType.INFORMATION)
                    refresh()
                }

                override fun onThrowable(error: Throwable) {
                    notifyUser(ideProject, "SeedBase login failed: ${error.message}", NotificationType.ERROR)
                }
            }.queue()
        }
    }

    private inner class LogoutAction : AnAction("Logout", "Remove the stored SeedBase token", AllIcons.Actions.Exit) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = TokenStore.get() != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            TokenStore.clear()
            notifyUser(ideProject, "SeedBase: logged out.", NotificationType.INFORMATION)
            refresh()
        }
    }

    private inner class RefreshAction : AnAction("Refresh", "Reload projects and generations", AllIcons.Actions.Refresh) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) {
            refresh()
        }
    }

    private inner class GenerateAction : AnAction("Generate Data", "Start a generation for the selected project", AllIcons.Actions.Execute) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = TokenStore.get() != null && selectedProjectNode() != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            val token = TokenStore.get() ?: return
            val project = selectedProjectNode() ?: return
            object : Task.Backgroundable(ideProject, "SeedBase: generating data for '${project.name}'…", true) {
                override fun run(indicator: ProgressIndicator) {
                    SeedbaseApi.generateAndWait(token, project.id) { indicator.checkCanceled() }
                }

                override fun onSuccess() {
                    notifyUser(ideProject, "SeedBase: generation completed for '${project.name}'.", NotificationType.INFORMATION)
                    refresh()
                }

                override fun onThrowable(error: Throwable) {
                    notifyUser(ideProject, "SeedBase generate failed: ${error.message}", NotificationType.ERROR)
                }
            }.queue()
        }
    }

    private data class SchemaCandidate(val label: String, val files: List<Path>, val sourceType: String)

    private fun scanSchemaCandidates(base: Path): List<SchemaCandidate> {
        val excluded = setOf(
            "venv", ".venv", "env", "node_modules", ".git", "migrations",
            "build", "dist", ".gradle", "__pycache__", ".idea", "vendor",
        )
        val models = mutableListOf<Path>()
        val prisma = mutableListOf<Path>()
        val sql = mutableListOf<Path>()
        Files.walk(base, 8).use { stream ->
            stream.asSequence()
                .filter { Files.isRegularFile(it) }
                .filter { path -> base.relativize(path).none { it.toString() in excluded } }
                .forEach { path ->
                    val name = path.fileName.toString().lowercase()
                    when {
                        name == "models.py" -> models.add(path)
                        name.endsWith(".prisma") -> prisma.add(path)
                        name.endsWith(".sql") -> sql.add(path)
                    }
                }
        }
        val candidates = mutableListOf<SchemaCandidate>()
        if (models.isNotEmpty()) {
            candidates.add(
                SchemaCandidate("Django models — ${models.size} × models.py (combined)", models.sorted(), "model_code"),
            )
        }
        prisma.sorted().forEach {
            candidates.add(SchemaCandidate(base.relativize(it).toString(), listOf(it), "model_code"))
        }
        sql.sorted().take(15).forEach {
            candidates.add(SchemaCandidate(base.relativize(it).toString(), listOf(it), "sql_dump"))
        }
        return candidates
    }

    private fun combineFiles(base: Path, files: List<Path>): String =
        files.joinToString("\n\n") { "# === ${base.relativize(it)} ===\n" + Files.readString(it) }

    private inner class PushSchemaAction : AnAction("Push Schema…", "Update the project schema from workspace files", AllIcons.Actions.Upload) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = TokenStore.get() != null && selectedProjectNode() != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            val token = TokenStore.get() ?: return
            val project = selectedProjectNode() ?: return
            val basePath = ideProject.basePath
            if (basePath == null) {
                Messages.showErrorDialog(ideProject, "No project directory available to scan.", "SeedBase")
                return
            }
            val base = Path.of(basePath)

            object : Task.Backgroundable(ideProject, "SeedBase: scanning for schema files…", false) {
                private var candidates: List<SchemaCandidate> = emptyList()

                override fun run(indicator: ProgressIndicator) {
                    candidates = scanSchemaCandidates(base)
                }

                override fun onSuccess() {
                    when {
                        candidates.isEmpty() -> notifyUser(
                            ideProject,
                            "SeedBase: no schema files (models.py / .prisma / .sql) found in this project.",
                            NotificationType.INFORMATION,
                        )
                        candidates.size == 1 -> detectAndApply(token, project, base, candidates.first())
                        else -> {
                            val byLabel = candidates.associateBy { it.label }
                            JBPopupFactory.getInstance()
                                .createPopupChooserBuilder(candidates.map { it.label })
                                .setTitle("Select Schema Source")
                                .setItemChosenCallback { label ->
                                    byLabel[label]?.let { detectAndApply(token, project, base, it) }
                                }
                                .createPopup()
                                .showCenteredInCurrentWindow(ideProject)
                        }
                    }
                }
            }.queue()
        }

        private fun detectAndApply(token: String, project: ProjectNode, base: Path, candidate: SchemaCandidate) {
            object : Task.Backgroundable(ideProject, "SeedBase: analyzing schema…", true) {
                private var content = ""
                private var tableCount = -1
                private var columnCount = -1

                override fun run(indicator: ProgressIndicator) {
                    content = combineFiles(base, candidate.files)
                    val detected = SeedbaseApi.importDetect(token, project.id, content, candidate.sourceType)
                    val summary = detected.get("summary")?.takeIf { it.isJsonObject }?.asJsonObject
                    tableCount = summary?.get("table_count")?.takeIf { it.isJsonPrimitive }?.asInt ?: -1
                    columnCount = summary?.get("column_count")?.takeIf { it.isJsonPrimitive }?.asInt ?: -1
                }

                override fun onSuccess() {
                    val what = if (tableCount >= 0) {
                        "Detected $tableCount table(s)" + (if (columnCount >= 0) " with $columnCount column(s)" else "")
                    } else {
                        "Schema analyzed"
                    }
                    val answer = Messages.showYesNoDialog(
                        ideProject,
                        "$what from '${candidate.label}'.\n\nApply to SeedBase project '${project.name}'? " +
                            "This replaces the project's current schema.",
                        "SeedBase: Push Schema",
                        "Apply",
                        "Cancel",
                        null,
                    )
                    if (answer == Messages.YES) {
                        apply(token, project, candidate.sourceType)
                    }
                }

                override fun onThrowable(error: Throwable) {
                    notifyUser(ideProject, "SeedBase schema analysis failed: ${error.message}", NotificationType.ERROR)
                }

                private fun apply(token: String, project: ProjectNode, sourceType: String) {
                    object : Task.Backgroundable(ideProject, "SeedBase: pushing schema to '${project.name}'…", false) {
                        private var warningCount = 0

                        override fun run(indicator: ProgressIndicator) {
                            val result = SeedbaseApi.importSchema(token, project.id, content, sourceType)
                            val warnings = result.get("warnings")?.takeIf { it.isJsonArray }?.asJsonArray
                            warningCount = warnings?.size() ?: 0
                        }

                        override fun onSuccess() {
                            val suffix = if (warningCount > 0) " ($warningCount warning(s) — review the schema in the browser)" else ""
                            notifyUser(ideProject, "SeedBase: schema pushed to '${project.name}'.$suffix", NotificationType.INFORMATION)
                            refresh()
                        }

                        override fun onThrowable(error: Throwable) {
                            notifyUser(ideProject, "SeedBase schema push failed: ${error.message}", NotificationType.ERROR)
                        }
                    }.queue()
                }
            }.queue()
        }
    }

    private inner class OpenInBrowserAction : AnAction("Open in Browser", "Open the project on seedba.se", AllIcons.General.Web) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selectedProjectNode() != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            val project = selectedProjectNode() ?: return
            BrowserUtil.browse("${SeedbaseApi.webBaseUrl()}/datasets/${project.id}/schema")
        }
    }

    private inner class PullAction(
        label: String,
        private val format: String,
        private val extension: String,
    ) : AnAction("Pull $label", "Download the selected generation as $label", AllIcons.Actions.Download) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            val generation = selectedGenerationNode()
            e.presentation.isEnabled =
                TokenStore.get() != null && generation != null && generation.status == "completed"
        }

        override fun actionPerformed(e: AnActionEvent) {
            val token = TokenStore.get() ?: return
            val generation = selectedGenerationNode() ?: return
            val basePath = ideProject.basePath
            if (basePath == null) {
                Messages.showErrorDialog(ideProject, "No project directory available to save into.", "SeedBase")
                return
            }
            val fileName = "seedbase-${generation.id.take(8)}.$extension"
            val target = Path.of(basePath, fileName)

            object : Task.Backgroundable(ideProject, "SeedBase: downloading $fileName…", true) {
                override fun run(indicator: ProgressIndicator) {
                    val data = SeedbaseApi.download(token, generation.id, format)
                    Files.write(target, data)
                }

                override fun onSuccess() {
                    val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target)
                    if (vf != null) {
                        FileEditorManager.getInstance(ideProject).openFile(vf, true)
                    }
                    notifyUser(ideProject, "SeedBase: saved $fileName.", NotificationType.INFORMATION)
                }

                override fun onThrowable(error: Throwable) {
                    notifyUser(ideProject, "SeedBase pull failed: ${error.message}", NotificationType.ERROR)
                }
            }.queue()
        }
    }
}
