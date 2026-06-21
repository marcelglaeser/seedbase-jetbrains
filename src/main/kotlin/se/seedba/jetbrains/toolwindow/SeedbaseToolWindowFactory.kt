package se.seedba.jetbrains.toolwindow

import com.google.gson.JsonArray
import com.google.gson.JsonElement
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
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.CheckBoxList
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.PopupHandler
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import com.intellij.ui.treeStructure.Tree
import se.seedba.jetbrains.api.SeedbaseApi
import se.seedba.jetbrains.api.SeedbaseApiException
import se.seedba.jetbrains.api.str
import se.seedba.jetbrains.auth.TokenStore
import se.seedba.jetbrains.db.LocalDb
import se.seedba.jetbrains.db.LocalDbStore
import se.seedba.jetbrains.settings.SeedbaseSettings
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.datatransfer.StringSelection
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.swing.BorderFactory
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.JTree
import javax.swing.table.DefaultTableModel
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
    val tableCount: Int get() {
        val schema = data.get("schema")?.takeIf { it.isJsonObject }?.asJsonObject ?: return 0
        val tables = schema.get("tables")?.takeIf { it.isJsonObject }?.asJsonObject ?: return 0
        return tables.size()
    }
}

private data class GenerationNode(val data: JsonObject, val projectId: String) {
    val id: String get() = data.str("id")
    val status: String get() = data.str("status").ifEmpty { "unknown" }
    val label: String get() = data.str("name").ifEmpty { id.take(8) }
}

private object LoggedOutNode {
    override fun toString(): String =
        "Not signed in. Create a free account at seedba.se (no card), then click the key icon to sign in."
}

private object EmptyGenerationsNode {
    override fun toString(): String = "No generations yet — generate data first"
}

private const val MAX_SCHEMA_CHARS = 4 * 1024 * 1024
private const val MAX_SQL_LINE_CHARS = 2_000_000

private fun notifyUser(project: Project?, message: String, type: NotificationType) {
    NotificationGroupManager.getInstance()
        .getNotificationGroup("SeedBase")
        .createNotification(message, type)
        .notify(project)
}

private class TableSelectionDialog(project: Project?, private val tables: List<String>) : DialogWrapper(project) {
    private val list = CheckBoxList<String>()
    private val selectAll = JCheckBox("Select all / none", true)

    init {
        title = "SeedBase: Select Tables to Send"
        setOKButtonText("Continue")
        tables.forEach { list.addItem(it, it, true) }
        selectAll.addActionListener {
            val state = selectAll.isSelected
            for (i in tables.indices) {
                list.getItemAt(i)?.let { list.setItemSelected(it, state) }
            }
            list.repaint()
        }
        init()
    }

    override fun createNorthPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.add(selectAll, BorderLayout.WEST)
        panel.border = BorderFactory.createEmptyBorder(0, 0, 6, 0)
        return panel
    }

    override fun createCenterPanel(): JComponent {
        val scroll = JBScrollPane(list)
        scroll.preferredSize = Dimension(360, 360)
        return scroll
    }

    fun selectedTables(): List<String> =
        tables.indices.filter { list.isItemSelected(it) }.map { tables[it] }
}

private class DataPreviewDialog(project: Project?, sample: JsonObject) : DialogWrapper(project) {
    private val dataObj: JsonObject = sample.get("data")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
    private val tableNames: List<String> = dataObj.keySet().toList()
    private val table = JBTable()
    private val selector = JComboBox(tableNames.toTypedArray())

    init {
        title = "SeedBase: Data Preview"
        setOKButtonText("Close")
        // Bei breiten Tabellen (viele Spalten) nicht in die Fensterbreite quetschen,
        // sondern echte Spaltenbreiten + horizontalen Scrollbalken.
        table.autoResizeMode = JTable.AUTO_RESIZE_OFF
        table.setShowGrid(true)
        selector.addActionListener { showTable(selector.selectedItem as? String) }
        init()
        tableNames.firstOrNull()?.let { showTable(it) }
    }

    private fun showTable(name: String?) {
        val rows = name?.let { dataObj.get(it)?.takeIf { e -> e.isJsonArray }?.asJsonArray } ?: JsonArray()
        val columns = LinkedHashSet<String>()
        for (el in rows) {
            if (el.isJsonObject) el.asJsonObject.keySet().forEach { columns.add(it) }
        }
        val cols = columns.toList()
        val model = object : DefaultTableModel(cols.toTypedArray(), 0) {
            override fun isCellEditable(row: Int, column: Int) = false
        }
        for (el in rows) {
            if (!el.isJsonObject) continue
            val obj = el.asJsonObject
            model.addRow(cols.map { c ->
                val v = obj.get(c)
                when {
                    v == null || v.isJsonNull -> ""
                    v.isJsonPrimitive -> v.asString
                    else -> v.toString()
                }
            }.toTypedArray())
        }
        table.model = model
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        if (tableNames.size > 1) {
            val north = JPanel(BorderLayout())
            north.add(selector, BorderLayout.WEST)
            north.border = BorderFactory.createEmptyBorder(0, 0, 6, 0)
            panel.add(north, BorderLayout.NORTH)
        }
        val scroll = JBScrollPane(table)
        scroll.preferredSize = Dimension(760, 440)
        panel.add(scroll, BorderLayout.CENTER)
        return panel
    }

    override fun createActions() = arrayOf(okAction)
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

        // Toolbar: nur wenige globale Aktionen. Alles Projekt-/Generierungs-
        // spezifische lebt im Rechtsklick-Menue (IDE-uebliches Muster).
        val toolbarGroup = DefaultActionGroup().apply {
            add(LoginAction())
            add(LogoutAction())
            addSeparator()
            add(RefreshAction())
            add(CreateProjectAction())
            add(GenerateAction())
            add(OpenInBrowserAction())
        }
        val popupGroup = DefaultActionGroup().apply {
            add(GenerateAction())
            add(PreviewDataAction())
            add(PushSchemaAction())
            add(InsertTestDataAction())
            addSeparator()
            add(MockApiAction())
            add(UploadContractAction())
            add(OpenInBrowserAction())
            addSeparator()
            add(SeedForTestsAction())
            add(SetDbConnectionAction())
            add(PushToDatabaseAction())
            addSeparator()
            add(PullAction())
            add(LoadIntoDatabaseAction())
            add(ResetReseedAction())
            add(DeleteGenerationAction())
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("SeedbaseToolbar", toolbarGroup, true)
        val panel = JPanel(BorderLayout())
        toolbar.targetComponent = panel
        panel.add(toolbar.component, BorderLayout.NORTH)
        panel.add(JBScrollPane(tree), BorderLayout.CENTER)
        component = panel

        PopupHandler.installPopupMenu(tree, popupGroup, "SeedbasePopup")

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
        ApplicationManager.getApplication().executeOnPooledThread {
            val token = TokenStore.get()
            if (token == null) {
                setNodes(listOf(DefaultMutableTreeNode(LoggedOutNode)))
                return@executeOnPooledThread
            }
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
            e.presentation.isEnabled = !TokenStore.isLoggedIn()
        }

        override fun actionPerformed(e: AnActionEvent) {
            object : Task.Backgroundable(ideProject, "SeedBase: signing in…", true) {
                override fun run(indicator: ProgressIndicator) {
                    val init = SeedbaseApi.initiateLogin()
                    if (init.browserUrl.isNotEmpty()) {
                        ApplicationManager.getApplication().invokeLater { BrowserUtil.browse(init.browserUrl) }
                    }
                    indicator.text = "SeedBase: waiting for authorization (code ${init.code})…"
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
            e.presentation.isEnabled = TokenStore.isLoggedIn()
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
            e.presentation.isEnabled = TokenStore.isLoggedIn() && selectedProjectNode() != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            val token = TokenStore.cachedToken() ?: return
            val project = selectedProjectNode() ?: return
            if (project.tableCount == 0) {
                Messages.showInfoMessage(
                    ideProject,
                    "Project '${project.name}' has no schema yet, so there is nothing to generate.\n\n" +
                        "Push a schema first via 'Push Schema…' or create the project with 'New Project from Schema'.",
                    "SeedBase: Generate Data",
                )
                return
            }
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
        files.joinToString("\n\n") { path ->
            val body = if (path.fileName.toString().lowercase().endsWith(".sql")) {
                readSqlDdl(path)
            } else {
                readCapped(path)
            }
            "# === ${base.relativize(path)} ===\n" + body
        }

    private fun readCapped(path: Path): String {
        val out = StringBuilder()
        BufferedReader(InputStreamReader(Files.newInputStream(path), StandardCharsets.UTF_8)).use { reader ->
            val buf = CharArray(1 shl 16)
            var n = reader.read(buf)
            while (n != -1 && out.length < MAX_SCHEMA_CHARS) {
                out.append(buf, 0, n)
                n = reader.read(buf)
            }
        }
        return out.toString()
    }

    private fun readSqlDdl(path: Path): String {
        val out = StringBuilder()
        var inCopy = false
        BufferedReader(InputStreamReader(Files.newInputStream(path), StandardCharsets.UTF_8)).use { reader ->
            val buf = CharArray(1 shl 16)
            val line = StringBuilder()
            var lineOverflow = false
            var n = reader.read(buf)
            loop@ while (n != -1) {
                for (i in 0 until n) {
                    val c = buf[i]
                    when {
                        c == '\n' -> {
                            if (!lineOverflow) {
                                inCopy = consumeSqlLine(out, line.toString(), inCopy)
                                if (out.length >= MAX_SCHEMA_CHARS) break@loop
                            }
                            line.setLength(0)
                            lineOverflow = false
                        }
                        c == '\r' -> {}
                        line.length < MAX_SQL_LINE_CHARS -> line.append(c)
                        else -> lineOverflow = true
                    }
                }
                n = reader.read(buf)
            }
            if (!lineOverflow && line.isNotEmpty() && out.length < MAX_SCHEMA_CHARS) {
                inCopy = consumeSqlLine(out, line.toString(), inCopy)
            }
        }
        return out.toString()
    }

    private fun consumeSqlLine(out: StringBuilder, raw: String, inCopy: Boolean): Boolean {
        val trimmed = raw.trimStart()
        val lower = trimmed.lowercase()
        if (inCopy) {
            return trimmed != "\\."
        }
        return when {
            lower.startsWith("copy ") && lower.contains(" from stdin") -> true
            lower.startsWith("insert into") -> false
            lower.startsWith("lock tables") || lower.startsWith("unlock tables") -> false
            else -> {
                out.append(raw).append('\n')
                false
            }
        }
    }

    private fun detectDbType(content: String, sourceType: String): String {
        val lower = content.lowercase()
        if (sourceType == "model_code") {
            val provider = Regex("""provider\s*=\s*["'](postgresql|postgres|mysql|sqlite|sqlserver|cockroachdb)["']""")
                .find(lower)?.groupValues?.getOrNull(1)
            return when (provider) {
                "mysql" -> "mysql"
                "sqlite" -> "sqlite"
                else -> "postgresql"
            }
        }
        return when {
            lower.contains("`") || Regex("""\bauto_increment\b""").containsMatchIn(lower) || lower.contains("engine=") -> "mysql"
            lower.contains("autoincrement") || Regex("""\bpragma\b""").containsMatchIn(lower) -> "sqlite"
            else -> "postgresql"
        }
    }

    private inner class PushSchemaAction : AnAction("Push Schema…", "Update the project schema from workspace files", AllIcons.Actions.Upload) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = TokenStore.isLoggedIn() && selectedProjectNode() != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            val token = TokenStore.cachedToken() ?: return
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
                private var tableNames: List<String> = emptyList()

                override fun run(indicator: ProgressIndicator) {
                    content = combineFiles(base, candidate.files)
                    val detected = SeedbaseApi.importDetect(token, project.id, content, candidate.sourceType)
                    tableNames = tableNamesFromDetect(detected)
                }

                override fun onSuccess() {
                    val includeTables: List<String>?
                    val sendingCount: Int
                    if (tableNames.size > 1) {
                        val dialog = TableSelectionDialog(ideProject, tableNames)
                        if (!dialog.showAndGet()) {
                            return
                        }
                        val selected = dialog.selectedTables()
                        if (selected.isEmpty()) {
                            notifyUser(ideProject, "SeedBase: no tables selected, nothing sent.", NotificationType.INFORMATION)
                            return
                        }
                        includeTables = if (selected.size == tableNames.size) null else selected
                        sendingCount = selected.size
                    } else {
                        includeTables = null
                        sendingCount = tableNames.size
                    }

                    val countLabel = if (tableNames.isNotEmpty()) "$sendingCount of ${tableNames.size} table(s)" else "the schema"
                    val answer = Messages.showYesNoDialog(
                        ideProject,
                        "Send $countLabel from '${candidate.label}' to SeedBase project '${project.name}'?\n\n" +
                            "This replaces the project's current schema.",
                        "SeedBase: Push Schema",
                        "Apply",
                        "Cancel",
                        null,
                    )
                    if (answer == Messages.YES) {
                        apply(token, project, candidate.sourceType, includeTables)
                    }
                }

                override fun onThrowable(error: Throwable) {
                    notifyUser(ideProject, "SeedBase schema analysis failed: ${error.message}", NotificationType.ERROR)
                }

                private fun apply(token: String, project: ProjectNode, sourceType: String, includeTables: List<String>?) {
                    object : Task.Backgroundable(ideProject, "SeedBase: pushing schema to '${project.name}'…", false) {
                        private var warningCount = 0

                        override fun run(indicator: ProgressIndicator) {
                            val result = SeedbaseApi.importSchema(token, project.id, content, sourceType, includeTables)
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

    private fun tableNamesFromDetect(detected: JsonObject): List<String> {
        val summary = detected.get("summary")?.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyList()
        val arr = summary.get("tables")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return arr.mapNotNull { el -> el.takeIf { it.isJsonPrimitive }?.asString }
    }

    private inner class CreateProjectAction : AnAction(
        "New Project from Schema…",
        "Create a SeedBase project from a schema file in this IDE project",
        AllIcons.General.Add,
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = TokenStore.isLoggedIn()
        }

        override fun actionPerformed(e: AnActionEvent) {
            val token = TokenStore.cachedToken() ?: return
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
                        candidates.size == 1 -> promptAndCreate(token, base, candidates.first())
                        else -> {
                            val byLabel = candidates.associateBy { it.label }
                            JBPopupFactory.getInstance()
                                .createPopupChooserBuilder(candidates.map { it.label })
                                .setTitle("Select Schema Source")
                                .setItemChosenCallback { label ->
                                    byLabel[label]?.let { promptAndCreate(token, base, it) }
                                }
                                .createPopup()
                                .showCenteredInCurrentWindow(ideProject)
                        }
                    }
                }
            }.queue()
        }

        private fun promptAndCreate(token: String, base: Path, candidate: SchemaCandidate, suggested: String = ideProject.name) {
            val name = Messages.showInputDialog(
                ideProject,
                "Project name:",
                "SeedBase: New Project",
                null,
                suggested,
                null,
            )?.trim()
            if (name.isNullOrEmpty()) {
                return
            }
            createWithSchema(token, base, candidate, name)
        }

        private fun createWithSchema(token: String, base: Path, candidate: SchemaCandidate, name: String) {
            object : Task.Backgroundable(ideProject, "SeedBase: creating project '$name'…", true) {
                private var projectId = ""
                private var content = ""
                private var tableNames: List<String> = emptyList()

                override fun run(indicator: ProgressIndicator) {
                    content = combineFiles(base, candidate.files)
                    val dbType = detectDbType(content, candidate.sourceType)
                    val created = SeedbaseApi.createProject(token, name, dbType)
                    projectId = created.str("id")
                    if (projectId.isEmpty()) {
                        throw SeedbaseApiException("Project creation did not return an id.")
                    }
                    val detected = SeedbaseApi.importDetect(token, projectId, content, candidate.sourceType)
                    tableNames = tableNamesFromDetect(detected)
                }

                override fun onSuccess() {
                    val includeTables: List<String>?
                    if (tableNames.size > 1) {
                        val dialog = TableSelectionDialog(ideProject, tableNames)
                        if (!dialog.showAndGet()) {
                            notifyUser(ideProject, "SeedBase: project '$name' created (empty — schema import cancelled).", NotificationType.INFORMATION)
                            refresh()
                            return
                        }
                        val selected = dialog.selectedTables()
                        if (selected.isEmpty()) {
                            notifyUser(ideProject, "SeedBase: project '$name' created (empty — no tables selected).", NotificationType.INFORMATION)
                            refresh()
                            return
                        }
                        includeTables = if (selected.size == tableNames.size) null else selected
                    } else {
                        includeTables = null
                    }
                    importInto(token, projectId, name, content, candidate.sourceType, includeTables)
                }

                override fun onThrowable(error: Throwable) {
                    val message = error.message ?: "Unknown error"
                    if (message.contains("already exists", ignoreCase = true)) {
                        Messages.showWarningDialog(
                            ideProject,
                            "A SeedBase project named '$name' already exists. Please choose a different name.",
                            "SeedBase: New Project",
                        )
                        promptAndCreate(token, base, candidate, "$name 2")
                    } else {
                        Messages.showErrorDialog(ideProject, message, "SeedBase: New Project")
                    }
                }
            }.queue()
        }

        private fun importInto(token: String, projectId: String, name: String, content: String, sourceType: String, includeTables: List<String>?) {
            object : Task.Backgroundable(ideProject, "SeedBase: importing schema into '$name'…", false) {
                override fun run(indicator: ProgressIndicator) {
                    SeedbaseApi.importSchema(token, projectId, content, sourceType, includeTables)
                }

                override fun onSuccess() {
                    notifyUser(ideProject, "SeedBase: project '$name' created from schema.", NotificationType.INFORMATION)
                    refresh()
                }

                override fun onThrowable(error: Throwable) {
                    notifyUser(ideProject, "SeedBase schema import failed: ${error.message}", NotificationType.ERROR)
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

    private inner class PullAction : AnAction("Pull Data…", "Download the selected generation as SQL, CSV or JSON", AllIcons.Actions.Download) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            val generation = selectedGenerationNode()
            e.presentation.isEnabled =
                TokenStore.isLoggedIn() && generation != null && generation.status == "completed"
        }

        override fun actionPerformed(e: AnActionEvent) {
            val token = TokenStore.cachedToken() ?: return
            val generation = selectedGenerationNode() ?: return
            val formats = listOf("SQL" to "sql", "CSV" to "csv", "JSON" to "json")
            JBPopupFactory.getInstance()
                .createPopupChooserBuilder(formats.map { it.first })
                .setTitle("Download Format")
                .setItemChosenCallback { label ->
                    val format = formats.first { it.first == label }.second
                    download(token, generation, format)
                }
                .createPopup()
                .showCenteredInCurrentWindow(ideProject)
        }

        private fun download(token: String, generation: GenerationNode, format: String) {
            val basePath = ideProject.basePath
            if (basePath == null) {
                Messages.showErrorDialog(ideProject, "No project directory available to save into.", "SeedBase")
                return
            }
            val fileName = "seedbase-${generation.id.take(8)}.$format"
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

    private fun chooseDbConnection(token: String, project: ProjectNode, onChosen: (JsonObject) -> Unit) {
        object : Task.Backgroundable(ideProject, "SeedBase: loading database connections…", true) {
            private var connections: List<JsonObject> = emptyList()

            override fun run(indicator: ProgressIndicator) {
                connections = SeedbaseApi.listDbConnections(token).filter { it.str("project") == project.id }
            }

            override fun onSuccess() {
                if (connections.isEmpty()) {
                    val answer = Messages.showYesNoDialog(
                        ideProject,
                        "No database connection for '${project.name}' yet. Connections are managed in the browser. Open it now?",
                        "SeedBase: Database",
                        "Open in Browser",
                        "Cancel",
                        null,
                    )
                    if (answer == Messages.YES) {
                        BrowserUtil.browse("${SeedbaseApi.webBaseUrl()}/datasets/${project.id}/data?tab=export&sub=db")
                    }
                    return
                }
                if (connections.size == 1) {
                    onChosen(connections.first())
                    return
                }
                val byLabel = connections.associateBy { "${it.str("name")} (${it.str("db_type")})" }
                JBPopupFactory.getInstance()
                    .createPopupChooserBuilder(byLabel.keys.toList())
                    .setTitle("Select Database Connection")
                    .setItemChosenCallback { label -> byLabel[label]?.let(onChosen) }
                    .createPopup()
                    .showCenteredInCurrentWindow(ideProject)
            }

            override fun onThrowable(error: Throwable) {
                notifyUser(ideProject, "SeedBase: ${error.message}", NotificationType.ERROR)
            }
        }.queue()
    }

    private fun pushToConnection(token: String, connection: JsonObject, strategy: String, title: String) {
        val connName = connection.str("name")
        object : Task.Backgroundable(ideProject, title, true) {
            private var rows = 0

            override fun run(indicator: ProgressIndicator) {
                val result = SeedbaseApi.pushAndWait(token, connection.str("id"), strategy) { indicator.checkCanceled() }
                rows = result.get("rows_pushed")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
            }

            override fun onSuccess() {
                val suffix = if (rows > 0) " ($rows rows)" else ""
                notifyUser(ideProject, "SeedBase: data loaded into '$connName'.$suffix", NotificationType.INFORMATION)
            }

            override fun onThrowable(error: Throwable) {
                notifyUser(ideProject, "SeedBase push to database failed: ${error.message}", NotificationType.ERROR)
            }
        }.queue()
    }

    private inner class PushToDatabaseAction : AnAction("Push to Database (server)…", "Load the generated data into a database connection configured on seedba.se (server-side)", AllIcons.Actions.Upload) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = TokenStore.isLoggedIn() && selectedProjectNode() != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            val token = TokenStore.cachedToken() ?: return
            val project = selectedProjectNode() ?: return
            chooseDbConnection(token, project) { connection ->
                val strategies = listOf(
                    "Drop & recreate (replace everything)" to "drop_recreate",
                    "Truncate first, then insert" to "truncate",
                    "Append (insert only)" to "append",
                )
                JBPopupFactory.getInstance()
                    .createPopupChooserBuilder(strategies.map { it.first })
                    .setTitle("Load Strategy")
                    .setItemChosenCallback { label ->
                        val strategy = strategies.first { it.first == label }.second
                        pushToConnection(token, connection, strategy, "SeedBase: loading data into '${connection.str("name")}'…")
                    }
                    .createPopup()
                    .showCenteredInCurrentWindow(ideProject)
            }
        }
    }

    // ---- Lokaler DB-Weg (wie VS Code): eigener Connection-String + psql/mysql/sqlite3 ----

    private fun resolveLocalConnection(): String? {
        LocalDbStore.get()?.let { return it }
        val detected = ideProject.basePath?.let { runCatching { LocalDb.detect(Path.of(it)) }.getOrNull() } ?: ""
        val entered = Messages.showInputDialog(
            ideProject,
            "Database connection string (postgres://, mysql:// or sqlite://):",
            "SeedBase: Database Connection",
            null,
            detected,
            null,
        )?.trim()
        if (entered.isNullOrEmpty()) return null
        LocalDbStore.set(entered)
        return entered
    }

    private fun runLocalLoad(token: String, generationId: String, conn: String, resetTables: Boolean, title: String) {
        object : Task.Backgroundable(ideProject, title, true) {
            override fun run(indicator: ProgressIndicator) {
                val sql = String(SeedbaseApi.download(token, generationId, "sql"), StandardCharsets.UTF_8)
                val finalSql = if (resetTables) {
                    LocalDb.truncateBlock(LocalDb.parseTableNames(sql), LocalDb.dialect(conn)) + sql
                } else {
                    sql
                }
                val tmp = Files.createTempFile("seedbase-", ".sql")
                Files.writeString(tmp, finalSql)
                try {
                    val cmd = LocalDb.buildCommand(conn, tmp.toString())
                    val (code, out) = LocalDb.runShell(cmd, ideProject.basePath)
                    if (code != 0) throw SeedbaseApiException("Database command failed (exit $code): ${out.takeLast(600)}")
                } finally {
                    runCatching { Files.deleteIfExists(tmp) }
                }
            }

            override fun onSuccess() {
                notifyUser(ideProject, "SeedBase: data loaded into the local database.", NotificationType.INFORMATION)
            }

            override fun onThrowable(error: Throwable) {
                notifyUser(ideProject, "SeedBase load failed: ${error.message}", NotificationType.ERROR)
            }
        }.queue()
    }

    private inner class SetDbConnectionAction : AnAction("Set Database Connection…", "Store a local database connection string (psql/mysql/sqlite)", AllIcons.General.Settings) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = TokenStore.isLoggedIn()
        }

        override fun actionPerformed(e: AnActionEvent) {
            val current = LocalDbStore.get() ?: ideProject.basePath?.let { runCatching { LocalDb.detect(Path.of(it)) }.getOrNull() } ?: ""
            val entered = Messages.showInputDialog(
                ideProject,
                "Local database connection string (leave empty to clear):",
                "SeedBase: Set Database Connection",
                null,
                current,
                null,
            ) ?: return
            if (entered.isBlank()) {
                LocalDbStore.clear()
                notifyUser(ideProject, "SeedBase: database connection cleared.", NotificationType.INFORMATION)
            } else {
                LocalDbStore.set(entered.trim())
                notifyUser(ideProject, "SeedBase: database connection saved.", NotificationType.INFORMATION)
            }
        }
    }

    private inner class LoadIntoDatabaseAction : AnAction("Load into Database…", "Run the selected generation's SQL into your local database (psql/mysql/sqlite)", AllIcons.Actions.Execute) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            val generation = selectedGenerationNode()
            e.presentation.isEnabled = TokenStore.isLoggedIn() && generation != null && generation.status == "completed"
        }

        override fun actionPerformed(e: AnActionEvent) {
            val token = TokenStore.cachedToken() ?: return
            val generation = selectedGenerationNode() ?: return
            val conn = resolveLocalConnection() ?: return
            runLocalLoad(token, generation.id, conn, false, "SeedBase: loading into database…")
        }
    }

    private inner class ResetReseedAction : AnAction("Reset & Reseed Database…", "Truncate the tables in your local database and reload the selected generation", AllIcons.Actions.Restart) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            val generation = selectedGenerationNode()
            e.presentation.isEnabled = TokenStore.isLoggedIn() && generation != null && generation.status == "completed"
        }

        override fun actionPerformed(e: AnActionEvent) {
            val token = TokenStore.cachedToken() ?: return
            val generation = selectedGenerationNode() ?: return
            val conn = resolveLocalConnection() ?: return
            val answer = Messages.showYesNoDialog(
                ideProject,
                "This DELETES all rows in the target tables and reloads fresh data. Continue?",
                "SeedBase: Reset & Reseed",
                "Reset & Reseed",
                "Cancel",
                Messages.getWarningIcon(),
            )
            if (answer == Messages.YES) {
                runLocalLoad(token, generation.id, conn, true, "SeedBase: reset & reseed…")
            }
        }
    }

    private inner class SeedForTestsAction : AnAction("Seed Test Data (generate + load)…", "Generate fresh data and load it into your local database in one step", AllIcons.Actions.RunAll) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            val project = selectedProjectNode()
            e.presentation.isEnabled = TokenStore.isLoggedIn() && project != null && project.tableCount > 0
        }

        override fun actionPerformed(e: AnActionEvent) {
            val token = TokenStore.cachedToken() ?: return
            val project = selectedProjectNode() ?: return
            val conn = resolveLocalConnection() ?: return
            object : Task.Backgroundable(ideProject, "SeedBase: generating data for '${project.name}'…", true) {
                private var generationId = ""

                override fun run(indicator: ProgressIndicator) {
                    generationId = SeedbaseApi.generateAndWait(token, project.id) { indicator.checkCanceled() }.str("id")
                }

                override fun onSuccess() {
                    refresh()
                    if (generationId.isNotEmpty()) {
                        runLocalLoad(token, generationId, conn, true, "SeedBase: loading into database…")
                    }
                }

                override fun onThrowable(error: Throwable) {
                    notifyUser(ideProject, "SeedBase seed failed: ${error.message}", NotificationType.ERROR)
                }
            }.queue()
        }
    }

    private inner class PreviewDataAction : AnAction("Preview Data", "Preview FK-consistent sample rows for this project", AllIcons.Actions.Preview) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = TokenStore.isLoggedIn() && selectedProjectNode() != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            val token = TokenStore.cachedToken() ?: return
            val project = selectedProjectNode() ?: return
            object : Task.Backgroundable(ideProject, "SeedBase: building preview…", true) {
                private var sample: JsonObject = JsonObject()

                override fun run(indicator: ProgressIndicator) {
                    sample = SeedbaseApi.sampleData(token, project.id, SeedbaseSettings.previewRows())
                }

                override fun onSuccess() {
                    val data = sample.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
                    if (data == null || data.size() == 0) {
                        notifyUser(ideProject, "SeedBase: nothing to preview — is the schema empty?", NotificationType.INFORMATION)
                        return
                    }
                    DataPreviewDialog(ideProject, sample).show()
                }

                override fun onThrowable(error: Throwable) {
                    notifyUser(ideProject, "SeedBase preview failed: ${error.message}", NotificationType.ERROR)
                }
            }.queue()
        }
    }

    private inner class MockApiAction : AnAction("Mock API…", "View, enable or configure this project's live mock REST API", AllIcons.Webreferences.Server) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = TokenStore.isLoggedIn() && selectedProjectNode() != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            val token = TokenStore.cachedToken() ?: return
            val project = selectedProjectNode() ?: return
            object : Task.Backgroundable(ideProject, "SeedBase: checking mock API…", true) {
                private var status: JsonObject = JsonObject()

                override fun run(indicator: ProgressIndicator) {
                    status = SeedbaseApi.mockApiStatus(token, project.id)
                }

                override fun onSuccess() {
                    if (status.get("active")?.takeIf { it.isJsonPrimitive }?.asBoolean == true) {
                        showActiveDialog(token, project, status)
                    } else {
                        pickGenerationThenEnable(token, project)
                    }
                }

                override fun onThrowable(error: Throwable) {
                    notifyUser(ideProject, "SeedBase mock API failed: ${error.message}", NotificationType.ERROR)
                }
            }.queue()
        }

        // Die Mock-API serviert die Daten EINER Generierung. Liegen mehrere vor,
        // den Nutzer waehlen lassen; sonst die neueste/automatisch.
        private fun pickGenerationThenEnable(token: String, project: ProjectNode) {
            object : Task.Backgroundable(ideProject, "SeedBase: loading generations…", true) {
                private var generations: List<JsonObject> = emptyList()

                override fun run(indicator: ProgressIndicator) {
                    generations = SeedbaseApi.listGenerations(token, project.id)
                        .filter { it.str("status") == "completed" }
                }

                override fun onSuccess() {
                    if (generations.size <= 1) {
                        enableThenShow(token, project, generations.firstOrNull()?.str("id"))
                        return
                    }
                    val byLabel = LinkedHashMap<String, String>()
                    generations.forEach { g ->
                        val name = g.str("name").ifEmpty { g.str("id").take(8) }
                        val rows = g.get("total_rows")?.takeIf { it.isJsonPrimitive }?.asInt
                        val created = formatDate(g.str("created_at"))
                        val label = listOfNotNull(name, rows?.let { "$it rows" }, created.ifEmpty { null }).joinToString(" · ")
                        byLabel[label] = g.str("id")
                    }
                    JBPopupFactory.getInstance()
                        .createPopupChooserBuilder(byLabel.keys.toList())
                        .setTitle("Serve which generation?")
                        .setItemChosenCallback { label -> enableThenShow(token, project, byLabel[label]) }
                        .createPopup()
                        .showCenteredInCurrentWindow(ideProject)
                }

                override fun onThrowable(error: Throwable) {
                    notifyUser(ideProject, "SeedBase mock API failed: ${error.message}", NotificationType.ERROR)
                }
            }.queue()
        }

        private fun enableThenShow(token: String, project: ProjectNode, generationId: String?) {
            object : Task.Backgroundable(ideProject, "SeedBase: enabling mock API…", true) {
                private var status: JsonObject = JsonObject()

                override fun run(indicator: ProgressIndicator) {
                    status = SeedbaseApi.enableMockApi(token, project.id, generationId)
                }

                override fun onSuccess() {
                    showActiveDialog(token, project, status)
                }

                override fun onThrowable(error: Throwable) {
                    notifyUser(ideProject, "SeedBase mock API failed: ${error.message}", NotificationType.ERROR)
                }
            }.queue()
        }

        private fun showActiveDialog(token: String, project: ProjectNode, status: JsonObject) {
            val baseUrl = status.str("base_url")
            val explorerUrl = status.str("explorer_url")
            val mode = status.str("mode").ifEmpty { "auto" }
            val isSpec = mode == "spec"
            val options = if (isSpec) {
                arrayOf("Open API Explorer", "Copy Base URL", "Disable", "Close")
            } else {
                arrayOf("Open API Explorer", "Copy Base URL", "Switch Generation", "Disable", "Close")
            }
            val choice = Messages.showDialog(
                ideProject,
                "Mock API for '${project.name}' is active (${mode} mode).\n\nBase URL:\n$baseUrl",
                "SeedBase: Mock API",
                options,
                0,
                AllIcons.Webreferences.Server,
            )
            // Im Spec-Modus gibt es keine 'Switch Generation'-Option -> Indizes anpassen.
            val disableIndex = if (isSpec) 2 else 3
            when {
                choice == 0 -> if (explorerUrl.isNotEmpty()) BrowserUtil.browse(explorerUrl)
                choice == 1 -> {
                    CopyPasteManager.getInstance().setContents(StringSelection(baseUrl))
                    notifyUser(ideProject, "SeedBase: base URL copied to clipboard.", NotificationType.INFORMATION)
                }
                !isSpec && choice == 2 -> pickGenerationThenEnable(token, project)
                choice == disableIndex -> object : Task.Backgroundable(ideProject, "SeedBase: disabling mock API…", false) {
                    override fun run(indicator: ProgressIndicator) {
                        SeedbaseApi.disableMockApi(token, project.id)
                    }

                    override fun onSuccess() {
                        notifyUser(ideProject, "SeedBase: mock API disabled for '${project.name}'.", NotificationType.INFORMATION)
                    }

                    override fun onThrowable(error: Throwable) {
                        notifyUser(ideProject, "SeedBase mock API failed: ${error.message}", NotificationType.ERROR)
                    }
                }.queue()
            }
        }
    }

    private inner class UploadContractAction : AnAction(
        "Upload OpenAPI…",
        "Serve your own OpenAPI/Swagger definition with FK-consistent mock data",
        AllIcons.Actions.Upload,
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = TokenStore.isLoggedIn() && selectedProjectNode() != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            val token = TokenStore.cachedToken() ?: return
            val project = selectedProjectNode() ?: return
            val basePath = ideProject.basePath
            if (basePath == null) {
                Messages.showErrorDialog(ideProject, "No project directory available to scan.", "SeedBase")
                return
            }
            val base = Path.of(basePath)
            object : Task.Backgroundable(ideProject, "SeedBase: scanning for OpenAPI files…", false) {
                private var specs: List<Path> = emptyList()

                override fun run(indicator: ProgressIndicator) {
                    specs = scanOpenApiCandidates(base)
                }

                override fun onSuccess() {
                    when {
                        specs.isEmpty() -> notifyUser(
                            ideProject,
                            "SeedBase: no OpenAPI file (openapi/swagger .json/.yaml/.yml) found in this project.",
                            NotificationType.INFORMATION,
                        )
                        specs.size == 1 -> uploadSpec(token, project, specs.first())
                        else -> {
                            val byLabel = specs.associateBy { base.relativize(it).toString() }
                            JBPopupFactory.getInstance()
                                .createPopupChooserBuilder(specs.map { base.relativize(it).toString() })
                                .setTitle("Select OpenAPI file")
                                .setItemChosenCallback { label -> byLabel[label]?.let { uploadSpec(token, project, it) } }
                                .createPopup()
                                .showCenteredInCurrentWindow(ideProject)
                        }
                    }
                }
            }.queue()
        }

        private fun uploadSpec(token: String, project: ProjectNode, path: Path) {
            object : Task.Backgroundable(ideProject, "SeedBase: uploading OpenAPI…", true) {
                private var explorerUrl = ""
                private var resourceCount = 0

                override fun run(indicator: ProgressIndicator) {
                    val text = readCapped(path)
                    val result = SeedbaseApi.uploadMockSpec(token, project.id, text)
                    explorerUrl = result.str("explorer_url")
                    resourceCount = result.get("resources")?.takeIf { it.isJsonArray }?.asJsonArray?.size() ?: 0
                }

                override fun onSuccess() {
                    val answer = Messages.showYesNoDialog(
                        ideProject,
                        "SeedBase: OpenAPI live with $resourceCount resource(s). Open the API Explorer?",
                        "SeedBase: Mock API",
                        "Open Explorer",
                        "Close",
                        null,
                    )
                    if (answer == Messages.YES && explorerUrl.isNotEmpty()) {
                        BrowserUtil.browse(explorerUrl)
                    }
                }

                override fun onThrowable(error: Throwable) {
                    notifyUser(ideProject, "SeedBase OpenAPI upload failed: ${error.message}", NotificationType.ERROR)
                }
            }.queue()
        }
    }

    private fun scanOpenApiCandidates(base: Path): List<Path> {
        val excluded = setOf("venv", ".venv", "node_modules", ".git", "build", "dist", ".gradle", "__pycache__", ".idea", "vendor")
        val out = mutableListOf<Path>()
        Files.walk(base, 8).use { stream ->
            stream.asSequence()
                .filter { Files.isRegularFile(it) }
                .filter { path -> base.relativize(path).none { it.toString() in excluded } }
                .forEach { path ->
                    val name = path.fileName.toString().lowercase()
                    val isSpecExt = name.endsWith(".json") || name.endsWith(".yaml") || name.endsWith(".yml")
                    if (isSpecExt && ("openapi" in name || "swagger" in name)) {
                        out.add(path)
                    }
                }
        }
        return out.sorted().take(20)
    }

    private inner class DeleteGenerationAction : AnAction("Delete Generation", "Delete the selected generation", AllIcons.Actions.GC) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = TokenStore.isLoggedIn() && selectedGenerationNode() != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            val token = TokenStore.cachedToken() ?: return
            val generation = selectedGenerationNode() ?: return
            val answer = Messages.showYesNoDialog(
                ideProject,
                "Delete generation '${generation.label}' (${generation.status})? This cannot be undone.",
                "SeedBase: Delete Generation",
                "Delete",
                "Cancel",
                Messages.getWarningIcon(),
            )
            if (answer != Messages.YES) {
                return
            }
            object : Task.Backgroundable(ideProject, "SeedBase: deleting generation…", false) {
                override fun run(indicator: ProgressIndicator) {
                    SeedbaseApi.deleteGeneration(token, generation.id)
                }

                override fun onSuccess() {
                    notifyUser(ideProject, "SeedBase: generation deleted.", NotificationType.INFORMATION)
                    refresh()
                }

                override fun onThrowable(error: Throwable) {
                    notifyUser(ideProject, "SeedBase delete failed: ${error.message}", NotificationType.ERROR)
                }
            }.queue()
        }
    }

    private inner class InsertTestDataAction : AnAction("Insert Test Data", "Insert FK-consistent sample rows at the caret", AllIcons.General.Add) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = TokenStore.isLoggedIn() && selectedProjectNode() != null &&
                FileEditorManager.getInstance(ideProject).selectedTextEditor != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            val token = TokenStore.cachedToken() ?: return
            val project = selectedProjectNode() ?: return
            val editor = FileEditorManager.getInstance(ideProject).selectedTextEditor ?: return
            val ext = FileDocumentManager.getInstance().getFile(editor.document)?.extension?.lowercase() ?: "json"
            object : Task.Backgroundable(ideProject, "SeedBase: fetching sample data…", true) {
                private var data: JsonObject? = null

                override fun run(indicator: ProgressIndicator) {
                    data = SeedbaseApi.sampleData(token, project.id, 5).getAsJsonObject("data")
                }

                override fun onSuccess() {
                    val d = data
                    if (d == null || d.size() == 0) {
                        notifyUser(ideProject, "SeedBase: no sample data (is the schema empty?).", NotificationType.WARNING)
                        return
                    }
                    val tables = d.keySet().toList()
                    if (tables.size == 1) {
                        insertForTable(editor, ext, tables.first(), d)
                    } else {
                        JBPopupFactory.getInstance()
                            .createPopupChooserBuilder(tables)
                            .setTitle("Insert test data for table")
                            .setItemChosenCallback { t -> insertForTable(editor, ext, t, d) }
                            .createPopup()
                            .showCenteredInCurrentWindow(ideProject)
                    }
                }

                override fun onThrowable(error: Throwable) {
                    notifyUser(ideProject, "SeedBase insert failed: ${error.message}", NotificationType.ERROR)
                }
            }.queue()
        }
    }

    private fun insertForTable(editor: com.intellij.openapi.editor.Editor, ext: String, table: String, data: JsonObject) {
        val arr = data.getAsJsonArray(table) ?: return
        val rows = arr.mapNotNull { it as? JsonObject }
        if (rows.isEmpty()) return
        val text = formatRows(table, rows, ext)
        WriteCommandAction.runWriteCommandAction(ideProject) {
            val offset = editor.caretModel.offset
            editor.document.insertString(offset, text)
            editor.caretModel.moveToOffset(offset + text.length)
        }
        notifyUser(ideProject, "SeedBase: inserted ${rows.size} '$table' row(s).", NotificationType.INFORMATION)
    }

    private fun columnsOf(rows: List<JsonObject>): List<String> {
        val cols = LinkedHashSet<String>()
        rows.forEach { row -> row.keySet().forEach(cols::add) }
        return cols.toList()
    }

    private fun cell(row: JsonObject, col: String): JsonElement = row.get(col) ?: com.google.gson.JsonNull.INSTANCE

    private fun formatRows(table: String, rows: List<JsonObject>, ext: String): String = when (ext) {
        "sql" -> formatSql(table, rows)
        "php" -> formatPhp(rows)
        "py" -> formatPy(rows)
        else -> formatJsonLike(rows)
    }

    private fun sqlLit(el: JsonElement): String = when {
        el.isJsonNull -> "NULL"
        el.isJsonPrimitive && el.asJsonPrimitive.isBoolean -> if (el.asBoolean) "TRUE" else "FALSE"
        el.isJsonPrimitive && el.asJsonPrimitive.isNumber -> el.asString
        else -> "'" + el.asString.replace("'", "''") + "'"
    }

    private fun formatSql(table: String, rows: List<JsonObject>): String {
        val cols = columnsOf(rows)
        val colSql = cols.joinToString(", ") { "`$it`" }
        val values = rows.joinToString(",\n") { row ->
            "  (" + cols.joinToString(", ") { c -> sqlLit(cell(row, c)) } + ")"
        }
        return "INSERT INTO `$table` ($colSql) VALUES\n$values;\n"
    }

    private fun phpLit(el: JsonElement): String = when {
        el.isJsonNull -> "null"
        el.isJsonPrimitive && el.asJsonPrimitive.isBoolean -> if (el.asBoolean) "true" else "false"
        el.isJsonPrimitive && el.asJsonPrimitive.isNumber -> el.asString
        else -> "'" + el.asString.replace("\\", "\\\\").replace("'", "\\'") + "'"
    }

    private fun formatPhp(rows: List<JsonObject>): String {
        val cols = columnsOf(rows)
        val items = rows.joinToString(",\n") { row ->
            "    [" + cols.joinToString(", ") { c -> "'$c' => " + phpLit(cell(row, c)) } + "]"
        }
        return "[\n$items,\n]\n"
    }

    private fun pyLit(el: JsonElement): String = when {
        el.isJsonNull -> "None"
        el.isJsonPrimitive && el.asJsonPrimitive.isBoolean -> if (el.asBoolean) "True" else "False"
        el.isJsonPrimitive && el.asJsonPrimitive.isNumber -> el.asString
        else -> "\"" + el.asString.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }

    private fun formatPy(rows: List<JsonObject>): String {
        val cols = columnsOf(rows)
        val items = rows.joinToString(",\n") { row ->
            "    {" + cols.joinToString(", ") { c -> "\"$c\": " + pyLit(cell(row, c)) } + "}"
        }
        return "[\n$items,\n]\n"
    }

    private fun formatJsonLike(rows: List<JsonObject>): String {
        val cols = columnsOf(rows)
        val items = rows.joinToString(",\n") { row ->
            "  {" + cols.joinToString(", ") { c -> "\"$c\": " + cell(row, c).toString() } + "}"
        }
        return "[\n$items\n]\n"
    }
}
