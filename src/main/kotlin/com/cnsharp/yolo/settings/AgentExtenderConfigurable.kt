package com.cnsharp.yolo.settings

import com.cnsharp.yolo.Yolo
import com.cnsharp.yolo.YoloBundle.message
import com.cnsharp.yolo.settings.AgentExtenderSettings
import com.cnsharp.yolo.terminal.AgentIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ContextHelpLabel
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.ui.table.JBTable
import com.cnsharp.yolo.util.baseName
import com.intellij.util.ui.FormBuilder
import javax.swing.event.TableModelEvent
import java.awt.Component
import java.awt.Color
import java.awt.BorderLayout
import java.awt.image.BufferedImage
import javax.swing.GrayFilter
import javax.swing.Icon
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JColorChooser
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableCellRenderer

/**
 * Settings | Tools | YOLO: AI Agents Extender
 *
 * A single merged agent list, where each row can configure its own "Skip flag" (permission-skip argument):
 *   - Agents promoted by this plugin (e.g. Claude Code, Codex, codebuddy; read-only, cannot be removed)
 *   - User custom tools (editable, addable/removable, icon specifiable)
 * The value of the "Skip flag" column is appended to the agent's launch command when the YOLO panel's
 * "Skip permissions" toggle is on.
 * Installed agents (on PATH) show their icon lit; uninstalled agents show a greyed icon. If an icon exists it is
 * shown, otherwise a default lightning bolt is used.
 */
class AgentExtenderConfigurable : Configurable {

    private val toolsModel = DefaultTableModel(
        arrayOf(
            message("column.icon"), message("column.id"), message("column.displayName"),
            message("column.command"), message("column.baseArgs"),
            message("column.skipFlag"), message("column.resumeFlag"), message("column.iconPath")
        ),
        0
    )

    private val toolsTable = AgentsTable(toolsModel)

    /** Lowercased id set of agents promoted by this plugin (e.g. claude/codex/codebuddy): read-only, not removable, icon fixed. */
    private var promotedIds: Set<String> = emptySet()

    private val statusLabel = JLabel("")

    /**
     * Terminal hyperlink color swatch: shows the current color and opens a standard color chooser on click.
     * Background holds the selected color; text shows its hex; foreground flips for contrast.
     */
    private val linkColorButton = JButton().apply {
        addActionListener {
            val chosen = JColorChooser.showDialog(this, message("settings.linkColor.title"), background)
            if (chosen != null) {
                linkColor = chosen
                modified = true
            }
        }
    }

    /** Selected terminal link color (alpha stripped, so it round-trips with the persisted RGB int). */
    private var linkColor: Color
        get() = Color(linkColorButton.background.rgb and 0xFFFFFF)
        set(value) {
            linkColorButton.background = value
            linkColorButton.foreground = if (isDark(value)) Color.WHITE else Color.BLACK
            linkColorButton.text = "#%06X".format(value.rgb and 0xFFFFFF)
        }

    private fun isDark(c: Color): Boolean =
        (0.299 * c.red + 0.587 * c.green + 0.114 * c.blue) < 128

    /** Settings checkbox mirroring [AgentExtenderSettings.State.skipWarningDismissed]: suppresses the
     *  "Skip permissions" launch warning when ticked. */
    private val skipWarnCheckBox = JBCheckBox(message("settings.skipWarning.label"))

    private var panel: JComponent? = null

    /** Whether there are unsaved changes: drives the platform Apply button's enabled/disabled state. */
    private var modified = false
    /** Temporarily suppress TableModelListener while reset() rebuilds the table, to avoid mistaking "loading" for "changes". */
    private var rebuilding = false
    /** setValueAt inside autoFillSkipFlag retriggers TableModelListener; this flag prevents recursive handling. */
    private var autoFilling = false

    override fun getDisplayName(): String = Yolo.NAME

    override fun createComponent(): JComponent {
        toolsTable.putClientProperty("terminateEditOnFocusLost", true)

        toolsTable.columnModel.getColumn(COL_ICON).cellRenderer = IconRenderer()
        toolsTable.columnModel.getColumn(COL_ICON).preferredWidth = 48
        toolsTable.columnModel.getColumn(COL_SKIP).preferredWidth = 200
        toolsTable.columnModel.getColumn(COL_RESUME).preferredWidth = 200
        // The icon path/URL is edited exclusively via the icon-setting row below the table, so hide this
        // grid column from the view. The underlying model column is kept — the icon button writes to it and
        // apply()/reset() still read it for persistence.
        toolsTable.removeColumn(toolsTable.columnModel.getColumn(COL_ICON_PATH))

        // Any cell change (add/remove row, edit, change icon) is marked as "unsaved changes", lighting the Apply button;
        // duplicate checks also run immediately so the user sees conflicts while editing, not only at Apply time.
        installListeners()

        // Keep the Providers button's enabled state in sync with the selected agent (only proxy-able + installed).
        toolsTable.selectionModel.addListSelectionListener { e ->
            if (e.valueIsAdjusting) return@addListSelectionListener
            updateProvidersButtonState()
        }

        val titleWithHelp = JPanel(HorizontalLayout(4)).apply {
            add(JBLabel(message("settings.agents.label")))
            add(ContextHelpLabel.create(message("settings.agents.help")))
        }

        val actionButtons = JPanel(HorizontalLayout(6)).apply {
            add(addToolButton())
            add(removeToolButton())
            add(validateButton())
            add(providersButton())
        }
        // Initialize the Providers button's enabled state now that it has been created.
        updateProvidersButtonState()

        panel = FormBuilder.createFormBuilder()
            .addLabeledComponent(titleWithHelp, JBScrollPane(toolsTable), true)
            .addComponent(actionButtons)
            .addComponent(iconChooserButton())
            .addComponent(statusLabel)
            .addLabeledComponent(JBLabel(message("settings.linkColor.label")), linkColorButton)
            .addComponent(skipWarnCheckBox)
            .panel
        reset()
        return panel!!
    }

    // ── Locked detection ─────────────────────────────────────────────

    /** Whether the selected row is a "locked" agent: IDEA built-in or promoted by this plugin (e.g. codebuddy).
     *  To the user these two are equivalent to built-in — read-only, cannot be removed, icon cannot be changed. */
    private fun isLockedAgent(row: Int): Boolean {
        if (row < 0) return false
        val id = (toolsModel.getValueAt(row, COL_ID) as? String)?.trim()?.lowercase() ?: ""
        return id in promotedIds
    }

    // ── Buttons ──────────────────────────────────────────────────────

    private fun addToolButton(): JComponent {
        val button = JButton(message("button.add"))
        button.toolTipText = message("button.add.tooltip")
        button.addActionListener {
            toolsModel.addRow(arrayOf<Any>("", "", "", "", "", "", "", ""))
            val last = toolsModel.rowCount - 1
            toolsTable.selectionModel.setSelectionInterval(last, last)
            toolsTable.scrollRectToVisible(toolsTable.getCellRect(last, COL_ID, true))
            setStatus(message("status.added"), warn = false)
        }
        return button
    }

    private fun removeToolButton(): JComponent {
        val button = JButton(message("button.remove"))
        button.toolTipText = message("button.remove.tooltip")
        button.addActionListener {
            val row = toolsTable.selectedRow
            if (row < 0) {
                setStatus(message("status.selectRow"), warn = true)
                return@addActionListener
            }
            if (isLockedAgent(row)) {
                val id = (toolsModel.getValueAt(row, COL_ID) as? String)?.trim() ?: ""
                setStatus(message("status.cannotRemove", id), warn = true)
                return@addActionListener
            }
            toolsModel.removeRow(row)
            setStatus(message("status.removed"), warn = false)
        }
        return button
    }

    /** "Providers…" opens the per-agent LLM provider dialog (2.0). Enabled only for an agent that is both
     *  installed and proxy-able (reads a custom LLM backend via env). See LlmProviderSupport. */
    private lateinit var providersButton: JButton
    private fun providersButton(): JComponent {
        val button = JButton(message("button.providers"))
        button.toolTipText = message("button.providers.tooltip")
        button.addActionListener {
            val row = toolsTable.selectedRow
            if (row < 0) {
                setStatus(message("status.selectRow"), warn = true)
                return@addActionListener
            }
            val id = (toolsModel.getValueAt(row, COL_ID) as? String)?.trim() ?: ""
            val command = (toolsModel.getValueAt(row, COL_COMMAND) as? String)?.trim() ?: ""
            val display = (toolsModel.getValueAt(row, COL_DISPLAY) as? String)?.trim() ?: id
            ProviderDialog(id, display, command).show()
        }
        providersButton = button
        return button
    }

    /** Reflect the selected agent's proxy-able/installed status into the Providers button's enabled state. */
    private fun updateProvidersButtonState() {
        val row = toolsTable.selectedRow
        val enabled = if (row < 0) false else {
            val command = (toolsModel.getValueAt(row, COL_COMMAND) as? String)?.trim() ?: ""
            LlmProviderSupport.isProviderConfigurable(
                command, AgentExtenderSettings.getInstance().state.installedCommands
            )
        }
        providersButton.isEnabled = enabled
        providersButton.toolTipText = if (enabled) {
            message("button.providers.tooltip")
        } else {
            message("button.providers.tooltip.disabled")
        }
    }

    private fun iconChooserButton(): JComponent {
        val textField = JBTextField().apply {
            toolTipText = message("icon.field.tooltip")
        }
        val browse = JButton(message("button.browse"))
        browse.toolTipText = message("button.browse.tooltip")
        fun syncEnabled() {
            val row = toolsTable.selectedRow
            val locked = isLockedAgent(row)
            textField.isEnabled = !locked
            browse.isEnabled = !locked
        }
        browse.addActionListener {
            // `withFileFilter` (since 2019.2) is long-stable and non-deprecated on 2023.3 (our min) through 2026.2.
            // `createSingleFileDescriptor(String)` (with a title) is the non-deprecated form; the no-arg overload
            // is deprecated since 2025.x, so use the titled overload here.
            val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("Select icon file")
                .withFileFilter { it.extension == "svg" || it.extension == "png" }
            val initial = textField.text?.takeIf { it.isNotBlank() && !it.startsWith("http", true) }?.let {
                LocalFileSystem.getInstance().findFileByPath(it)
            }
            FileChooser.chooseFile(descriptor, null, initial) { virtualFile ->
                textField.text = virtualFile.path
                writeSelectedRowIcon(textField.text)
            }
        }
        textField.addActionListener {
            if (toolsTable.selectedRow >= 0) writeSelectedRowIcon(textField.text)
        }
        toolsTable.selectionModel.addListSelectionListener { e ->
            if (e.valueIsAdjusting) return@addListSelectionListener
            val row = toolsTable.selectedRow
            textField.text = if (row >= 0) (toolsModel.getValueAt(row, COL_ICON_PATH) as? String) ?: "" else ""
            syncEnabled()
        }
        syncEnabled()
        textField.columns = 30
        // Label on the left, the path textbox fills the middle, and Browse sits on the right.
        val right = JPanel(com.intellij.ui.components.panels.HorizontalLayout(6)).apply {
            add(browse)
        }
        val p = JPanel(BorderLayout(6, 0)).apply {
            add(JLabel(message("icon.label")), BorderLayout.WEST)
            add(textField, BorderLayout.CENTER)
            add(right, BorderLayout.EAST)
        }
        return p
    }

    private fun writeSelectedRowIcon(path: String) {
        val row = toolsTable.selectedRow
        if (row < 0) return
        if (isLockedAgent(row)) {
            setStatus(message("status.cannotReIcon"), warn = true)
            return
        }
        toolsModel.setValueAt(path, row, COL_ICON_PATH)
    }

    /** Validate the selected row (or the whole table if none selected): whether the command resolves on PATH + whether the icon (downloaded if a URL) is in place. */
    private fun validateButton(): JComponent {
        val button = JButton(message("button.validate"))
        button.toolTipText = message("button.validate.tooltip")
        button.addActionListener {
            val rows = if (toolsTable.selectedRow >= 0) listOf(toolsTable.selectedRow)
            else (0 until toolsModel.rowCount).toList()
            if (rows.isEmpty()) {
                setStatus(message("status.nothingToValidate"), warn = true)
                return@addActionListener
            }
            val entries = rows.mapNotNull { r ->
                val id = (toolsModel.getValueAt(r, COL_ID) as? String)?.trim() ?: ""
                val command = (toolsModel.getValueAt(r, COL_COMMAND) as? String)?.trim() ?: ""
                val icon = (toolsModel.getValueAt(r, COL_ICON_PATH) as? String)?.trim() ?: ""
                if (id.isBlank() && command.isBlank()) null else RowInput(r, id, command, icon)
            }
            if (entries.isEmpty()) {
                setStatus(message("status.nothingToValidate"), warn = true)
                return@addActionListener
            }
            setStatus(message("status.validating"), warn = false)
            val app = ApplicationManager.getApplication()
            app.executeOnPooledThread {
                val results = entries.map { e ->
                    val cmd = CommandValidator.validate(e.command)
                    val icon = IconResolver.resolve(e.icon, e.id)
                    // After archiving the path becomes <id>.svg/png; write it back to the table so the user sees the final location
                    val archived = (icon as? IconResolver.Result.Local)
                        ?.path?.takeIf { it.isNotEmpty() && it != e.icon }
                    e to Triple(cmd, icon, archived)
                }
                app.invokeLater {
                    results.forEach { (e, triple) ->
                        val downloaded = triple.third
                        if (downloaded != null) toolsModel.setValueAt(downloaded, e.row, COL_ICON_PATH)
                    }
                    reportValidation(results)
                }
            }
        }
        return button
    }

    private fun reportValidation(results: List<Pair<RowInput, Triple<CommandValidator.Result, IconResolver.Result, String?>>>) {
        val lines = results.map { (e, triple) ->
            val (cmd, icon) = triple.first to triple.second
            val cmdText = when (cmd) {
                is CommandValidator.Result.Ok -> message("validation.command.ok")
                is CommandValidator.Result.NotFound -> message("validation.command.notFound", cmd.detail)
            }
            val iconText = when (icon) {
                is IconResolver.Result.Local ->
                    if (icon.path.isEmpty()) message("validation.icon.none") else message("validation.icon.ok")
                is IconResolver.Result.Error -> message("validation.icon.error", icon.message)
            }
            "· ${e.id.ifBlank { e.command }}: $cmdText; $iconText"
        }
        val allCmdOk = results.all { it.second.first is CommandValidator.Result.Ok }
        val allIconOk = results.all {
            it.second.second is IconResolver.Result.Local
        }
        val warn = !(allCmdOk && allIconOk)
        val prefix = message(if (warn) "status.validation.issues" else "status.validation.passed")
        setStatus(prefix + lines.first(), warn = warn)
        NOTIFY.createNotification(
            Yolo.NAME,
            lines.joinToString("<br>"),
            if (warn) NotificationType.WARNING else NotificationType.INFORMATION
        ).notify(null)
    }

    private fun setStatus(text: String, warn: Boolean) {
        statusLabel.text = text
        statusLabel.foreground = if (warn) JBColor.RED else JBColor.GREEN
    }

    // ── Duplicate check ──────────────────────────────────────────────

    /**
     * Find duplicate ID / Command.
     *
     * IDs are compared directly by lowercase; Commands are compared after [baseName] — because injection matches
     * by executable filename, `/usr/local/bin/claude` and `claude.cmd` actually point to the same agent and are a
     * genuine duplicate.
     * Empty values are not compared (a newly added blank row is not yet filled in and should not error immediately).
     */
    private fun findDuplicates(): List<String> {
        val ids = HashMap<String, Int>()
        val cmds = HashMap<String, Int>()
        val dupIds = LinkedHashSet<String>()
        val dupCmds = LinkedHashSet<String>()
        for (r in 0 until toolsModel.rowCount) {
            val id = (toolsModel.getValueAt(r, COL_ID) as? String)?.trim() ?: ""
            val cmd = (toolsModel.getValueAt(r, COL_COMMAND) as? String)?.trim() ?: ""
            if (id.isNotEmpty() && ids.put(id.lowercase(), r) != null) dupIds += id
            if (cmd.isNotEmpty() && cmds.put(baseName(cmd).lowercase(), r) != null) dupCmds += cmd
        }
        val problems = mutableListOf<String>()
        if (dupIds.isNotEmpty()) problems += message("duplicate.id", dupIds.joinToString(", "))
        if (dupCmds.isNotEmpty()) problems += message("duplicate.command", dupCmds.joinToString(", "))
        return problems
    }

    /** Prompt duplicates immediately during editing; clear the previous duplicate warning when there are none. */
    private fun reportDuplicates() {
        val problems = findDuplicates()
        if (problems.isNotEmpty()) {
            setStatus(problems.joinToString("; "), warn = true)
        } else if (statusLabel.foreground == JBColor.RED) {
            setStatus("", warn = false)
        }
    }

    /** Register table-change listener: mark "modified", check duplicates live, and auto-prefill the Skip flag for known agents.
     *  Extracted separately so it can be called directly in headless test environments (the ContextHelpLabel inside
     *  createComponent depends on IDEA's CoroutineScope service, which MockApplication does not provide). */
    private fun installListeners() {
        toolsModel.addTableModelListener { e ->
            if (rebuilding || autoFilling) return@addTableModelListener
            modified = true
            reportDuplicates()
            // When the user adds/edits a row's ID or Command, if the tool is a known agent and its Skip flag column is
            // still empty, prefill a default from AgentRegistry — so a newly added agent gets its skip flag configured
            // without manual copying.
            // Env-type agents (goose) do not go here; their bypass is injected as an env var by AgentRegistry.skipEnvFor.
            if (e.type == TableModelEvent.UPDATE && (e.column == COL_ID || e.column == COL_COMMAND)) {
                autoFillSkipFlag(e.firstRow)
                autoFillResumeFlag(e.firstRow)
            }
        }
    }

    /** When the user adds/edits a row's ID or Command, if the tool is a known agent and its Skip flag column is still
     *  empty, prefill a default from AgentRegistry (prefer by command binary name, fall back to ID).
     *  Only fills when the column is still empty; values the user manually cleared or changed are not overwritten. */
    private fun autoFillSkipFlag(row: Int) {
        if (row < 0 || row >= toolsModel.rowCount) return
        val id = (toolsModel.getValueAt(row, COL_ID) as? String)?.trim() ?: ""
        val command = (toolsModel.getValueAt(row, COL_COMMAND) as? String)?.trim() ?: ""
        val current = (toolsModel.getValueAt(row, COL_SKIP) as? String)?.trim() ?: ""
        if (current.isNotEmpty()) return
        val byCmd = if (command.isNotBlank()) AgentRegistry.skipFlagFor(baseName(command)) else ""
        val resolved = if (byCmd.isNotEmpty()) byCmd else AgentRegistry.skipFlagFor(id)
        if (resolved.isNotEmpty()) {
            autoFilling = true
            try {
                toolsModel.setValueAt(resolved, row, COL_SKIP)
            } finally {
                autoFilling = false
            }
        }
    }

    /** Same auto-prefill behavior as [autoFillSkipFlag], but for the Resume flag column (COL_RESUME), reading
     *  AgentRegistry.resumeFlagFor. Custom tools have no agents.json entry, so for them this column stays empty
     *  until the user fills it in. */
    private fun autoFillResumeFlag(row: Int) {
        if (row < 0 || row >= toolsModel.rowCount) return
        val id = (toolsModel.getValueAt(row, COL_ID) as? String)?.trim() ?: ""
        val command = (toolsModel.getValueAt(row, COL_COMMAND) as? String)?.trim() ?: ""
        val current = (toolsModel.getValueAt(row, COL_RESUME) as? String)?.trim() ?: ""
        if (current.isNotEmpty()) return
        val byCmd = if (command.isNotBlank()) AgentRegistry.resumeFlagFor(baseName(command)) else ""
        val resolved = if (byCmd.isNotEmpty()) byCmd else AgentRegistry.resumeFlagFor(id)
        if (resolved.isNotEmpty()) {
            autoFilling = true
            try {
                toolsModel.setValueAt(resolved, row, COL_RESUME)
            } finally {
                autoFilling = false
            }
        }
    }

    // ── Install status (icon lit / greyed) ──────────────────────────

    /** Render each row's icon lit/greyed from the shared installed-agents cache, then refresh the cache in the
     *  background; only re-render when the detected set changes — the same mechanism as the terminal dropdown. */
    private fun refreshInstalledFlags() {
        val commands = (0 until toolsModel.rowCount).map { r ->
            (toolsModel.getValueAt(r, COL_COMMAND) as? String)?.trim() ?: ""
        }
        // Instant render from the cache...
        toolsTable.setInstalled(flagsFor(commands, InstalledAgents.installed()))
        // ...then refresh the cache and re-render only if the installed set actually changed.
        InstalledAgents.rescan(commands) { set ->
            toolsTable.setInstalled(flagsFor(commands, set))
        }
    }

    /** Build the per-row "installed" flags: a command is installed iff it is non-blank and present (lower-cased) in [installed]. */
    private fun flagsFor(commands: List<String>, installed: Set<String>): BooleanArray =
        BooleanArray(commands.size) { i ->
            commands[i].isNotEmpty() && commands[i].lowercase() in installed
        }

    // ── Persistence ──────────────────────────────────────────────────

    override fun isModified(): Boolean {
        val state = AgentExtenderSettings.getInstance().state
        return modified
            || (linkColor.rgb and 0xFFFFFF) != state.linkColorRgb
            || skipWarnCheckBox.isSelected != state.skipWarningDismissed
    }

    override fun apply() {
        // Duplicate ID/Command would create two same-named entries in the dropdown and skip rules would overwrite each other, so reject saving outright
        findDuplicates().takeIf { it.isNotEmpty() }?.let {
            val text = it.joinToString("; ")
            setStatus(text, warn = true)
            throw ConfigurationException(text, Yolo.NAME)
        }

        val settings = AgentExtenderSettings.getInstance()

        // ① Permission rules: from each row's Skip flag (matched by command binary name so the customizer can hit it)
        val newRules = mutableListOf<PermissionRule>()
        for (r in 0 until toolsModel.rowCount) {
            val command = (toolsModel.getValueAt(r, COL_COMMAND) as? String)?.trim() ?: ""
            val flag = (toolsModel.getValueAt(r, COL_SKIP) as? String)?.trim() ?: ""
            if (command.isNotBlank() && flag.isNotBlank()) {
                newRules.add(PermissionRule(agentId = baseName(command), flag = flag))
            }
        }
        settings.state.permissionRules = newRules

        // ①b Resume rules: from each row's Resume flag (same matching by command binary name; empty column falls back to agents.json).
        val newResumeRules = mutableListOf<ResumeRule>()
        for (r in 0 until toolsModel.rowCount) {
            val command = (toolsModel.getValueAt(r, COL_COMMAND) as? String)?.trim() ?: ""
            val flag = (toolsModel.getValueAt(r, COL_RESUME) as? String)?.trim() ?: ""
            if (command.isNotBlank() && flag.isNotBlank()) {
                newResumeRules.add(ResumeRule(agentId = baseName(command), flag = flag))
            }
        }
        settings.state.resumeRules = newResumeRules

        // ①b Terminal hyperlink color (alpha stripped so it round-trips with the persisted RGB int).
        settings.state.linkColorRgb = linkColor.rgb and 0xFFFFFF

        // ①c Suppress the "Skip permissions" launch warning when the user ticks the checkbox.
        settings.state.skipWarningDismissed = skipWarnCheckBox.isSelected

        // ② Custom tools: skip promoted agents (they are not written to customTools); only save user custom tools
        settings.state.customTools = (0 until toolsModel.rowCount).mapNotNull { r ->
            val id = (toolsModel.getValueAt(r, COL_ID) as? String)?.trim() ?: ""
            val command = (toolsModel.getValueAt(r, COL_COMMAND) as? String)?.trim() ?: ""
            if (id.isBlank() || command.isBlank()) null
            else if (id.lowercase() in promotedIds) null
            else CustomTool(
                id = id,
                displayName = (toolsModel.getValueAt(r, COL_DISPLAY) as? String)?.trim() ?: id,
                command = command,
                baseArgs = (toolsModel.getValueAt(r, COL_BASE_ARGS) as? String) ?: "",
                iconPath = (toolsModel.getValueAt(r, COL_ICON_PATH) as? String)?.trim() ?: ""
            )
        }.toMutableList()

        // ②b Persist "Base args" overrides for promoted agents (codebuddy, …). They are deliberately NOT
        //    written into customTools (section ②), so a dedicated map is needed — otherwise an edit to a
        //    promoted agent's Base args column would be silently discarded on Apply.
        val baseArgsMap = settings.state.agentBaseArgs
        baseArgsMap.clear()
        for (r in 0 until toolsModel.rowCount) {
            val id = (toolsModel.getValueAt(r, COL_ID) as? String)?.trim() ?: ""
            if (id.lowercase() !in promotedIds) continue
            val baseArgs = (toolsModel.getValueAt(r, COL_BASE_ARGS) as? String)?.trim() ?: ""
            if (baseArgs.isNotEmpty()) baseArgsMap[id.lowercase()] = baseArgs
        }

        AgentIcons.clearCache()

        modified = false

        // Notify the live terminal panel (if open) so its dropdown reflects the new settings — e.g. cleared base args.
        settings.fireChanged()

        ApplicationManager.getApplication().executeOnPooledThread {
            finalizeIcons(settings)
        }
    }

    private fun finalizeIcons(settings: AgentExtenderSettings) {
        var changed = false
        for (tool in settings.state.customTools) {
            val icon = tool.iconPath.trim()
            if (icon.isEmpty()) continue
            // Both URLs and local files are archived to <id>.svg/png; already-archived ones are returned as-is by IconResolver
            when (val r = IconResolver.resolve(icon, tool.id)) {
                is IconResolver.Result.Local ->
                    if (r.path.isNotEmpty() && r.path != icon) { tool.iconPath = r.path; changed = true }
                is IconResolver.Result.Error ->
                    com.intellij.openapi.diagnostic.Logger.getInstance(AgentExtenderConfigurable::class.java)
                        .warn("${Yolo.NAME}: icon resolve failed ${r.message}")
            }
        }
        if (changed) {
            ApplicationManager.getApplication().invokeLater {
                AgentIcons.clearCache()
            }
        }
    }

    override fun reset() {
        // Suppress TableModelListener while rebuilding the table, to avoid mistaking "loading" for "changes"
        rebuilding = true
        try {
            toolsModel.rowCount = 0
            val state = AgentExtenderSettings.getInstance().state
            // Index existing permission rules by "command binary name" and write back to each row's Skip flag column
            val ruleByCmd = state.permissionRules.associateBy({ baseName(it.agentId).lowercase() }, { it.flag })
            /** Prefer the user-saved rule; fall back to AgentRegistry (by command name, then by id) if empty. */
            fun flagFor(cmd: String, id: String = ""): String {
                val saved = ruleByCmd[baseName(cmd).lowercase()]
                if (!saved.isNullOrBlank()) return@flagFor saved
                return AgentRegistry.skipFlagFor(baseName(cmd)).ifBlank {
                    AgentRegistry.skipFlagFor(id)
                }
            }

            // Index existing resume rules by "command binary name" and write back to each row's Resume flag column.
            val resumeRuleByCmd = state.resumeRules.associateBy({ baseName(it.agentId).lowercase() }, { it.flag })
            /** Prefer the user-saved rule; fall back to AgentRegistry (by command name, then by id) if empty. */
            fun resumeFor(cmd: String, id: String = ""): String {
                val saved = resumeRuleByCmd[baseName(cmd).lowercase()]
                if (!saved.isNullOrBlank()) return@resumeFor saved
                return AgentRegistry.resumeFlagFor(baseName(cmd)).ifBlank {
                    AgentRegistry.resumeFlagFor(id)
                }
            }

            // ① Promoted by this plugin (e.g. claude/codex/codebuddy): in AgentRegistry.agents order (Claude Code, Codex pinned Top 2),
            //    read-only, not removable, icon fixed.
            promotedIds = AgentRegistry.agents.map { it.id.lowercase() }.toSet()
            for (def in AgentRegistry.agents) {
                val baseArgs = state.agentBaseArgs[def.id.lowercase()] ?: ""
                toolsModel.addRow(
                    arrayOf<Any>(
                        "", def.id, def.displayName, def.command,
                        baseArgs, flagFor(def.command, def.id), resumeFor(def.command, def.id), ""
                    )
                )
            }
            // ② User custom tools (skip entries whose id duplicates a promoted agent).
            //    Sorted by creation time — each user addition appends to the list end, there is no row-reorder UI, so saved
            //    order equals creation order; just add in state.customTools order (lowest priority).
            for (tool in state.customTools) {
                val lid = tool.id.lowercase()
                if (lid in promotedIds) continue
                toolsModel.addRow(
                    arrayOf<Any>(
                        "", tool.id, tool.displayName, tool.command,
                        tool.baseArgs, flagFor(tool.command), resumeFor(tool.command, tool.id), tool.iconPath
                    )
                )
            }
            refreshInstalledFlags()
            linkColor = Color(state.linkColorRgb)
            skipWarnCheckBox.isSelected = state.skipWarningDismissed
            setStatus("", warn = false)
        } finally {
            rebuilding = false
            modified = false
        }
    }

    // ── Renderers ───────────────────────────────────────────────────

    /** Icon column: built-in agents use IDEA's own icon; custom tools use AgentIcons (default lightning bolt if none).
     *  When not installed (installedFlags is false) render the icon greyed; when installed render it normally lit. */
    private inner class IconRenderer : TableCellRenderer {
        private val label = JLabel()
        override fun getTableCellRendererComponent(
            table: javax.swing.JTable?,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int
        ): Component {
            label.icon = null
            label.text = ""
            if (table != null && row in 0 until toolsModel.rowCount) {
                val id = (toolsModel.getValueAt(row, COL_ID) as? String)?.lowercase() ?: ""
                val iconPath = (toolsModel.getValueAt(row, COL_ICON_PATH) as? String) ?: ""
                val baseIcon = AgentIcons.forAgent(id, iconPath)
                val installed = toolsTable.installedFlags.getOrElse(row) { false }
                label.icon = if (installed) baseIcon else greyed(baseIcon)
            }
            label.horizontalAlignment = SwingConstants.CENTER
            label.background = if (isSelected) table?.selectionBackground else table?.background
            label.foreground = if (isSelected) table?.selectionForeground else table?.foreground
            label.isOpaque = true
            return label
        }
    }

    /** Draw the icon onto a BufferedImage and use GrayFilter to generate a disabled (greyed) version. */
    private fun greyed(icon: Icon): Icon {
        if (icon.iconWidth <= 0 || icon.iconHeight <= 0) return icon
        val img = BufferedImage(icon.iconWidth, icon.iconHeight, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        icon.paintIcon(null, g, 0, 0)
        g.dispose()
        return ImageIcon(GrayFilter.createDisabledImage(img))
    }

    /** Merged-list table: locked agents' ID/Command are read-only; rows that are not installed are greyed out (not lit) entirely. */
    private inner class AgentsTable(model: javax.swing.table.TableModel) : JBTable(model) {
        var installedFlags: BooleanArray = BooleanArray(0)

        fun setInstalled(flags: BooleanArray) {
            installedFlags = flags
            repaint()
        }

        override fun isCellEditable(row: Int, column: Int): Boolean {
            if (column == COL_ICON) return false
            if (column == COL_ID || column == COL_COMMAND) {
                val id = (model.getValueAt(row, COL_ID) as? String)?.trim()?.lowercase() ?: ""
                // Built-in / promoted agents' ID / Command are fixed and not editable
                if (id in promotedIds) return false
            }
            return super.isCellEditable(row, column)
        }

        override fun prepareRenderer(renderer: TableCellRenderer, row: Int, column: Int): Component {
            val c = super.prepareRenderer(renderer, row, column)
            if (isRowSelected(row)) return c
            val inst = installedFlags.getOrElse(row) { true }
            if (!inst && c is JComponent) {
                c.foreground = JBColor.GRAY
            }
            return c
        }
    }

    private data class RowInput(
        val row: Int,
        val id: String,
        val command: String,
        val icon: String
    )

    companion object {
        private const val COL_ICON = 0
        private const val COL_ID = 1
        private const val COL_DISPLAY = 2
        private const val COL_COMMAND = 3
        private const val COL_BASE_ARGS = 4
        private const val COL_SKIP = 5
        private const val COL_RESUME = 6
        private const val COL_ICON_PATH = 7

        private val NOTIFY: com.intellij.notification.NotificationGroup by lazy {
            NotificationGroupManager.getInstance().getNotificationGroup("com.cnsharp.yolo.notifications")
        }
    }
}
