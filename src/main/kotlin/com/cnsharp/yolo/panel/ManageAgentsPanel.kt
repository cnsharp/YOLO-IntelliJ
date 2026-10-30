package com.cnsharp.yolo.panel

import com.cnsharp.yolo.YoloBundle.message
import com.cnsharp.yolo.settings.AgentDef
import com.cnsharp.yolo.settings.AgentDetector
import com.cnsharp.yolo.settings.AgentExtenderSettings
import com.cnsharp.yolo.settings.AgentExtenderSettingsListener
import com.cnsharp.yolo.settings.AgentRegistry
import com.cnsharp.yolo.settings.AgentUpdateChecker
import com.cnsharp.yolo.settings.InstalledAgents
import com.cnsharp.yolo.settings.InstallSpec
import com.cnsharp.yolo.terminal.AgentIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.JBColor
import com.intellij.ui.ColorUtil
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.WrapLayout
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent

/**
 * "Manage Agents" tab for the YOLO tool window — mirrors the VSCode product's Agent Manager page:
 * a card grid where each agent shows its icon, name, current/latest version and an install / update /
 * website action. Versions come from [AgentUpdateChecker] (cache-first; refreshed on "Check Updates").
 *
 * The host owns install/update execution ([AgentUpdateChecker.run] runs the package-manager command through
 * the user's login shell so nvm / /usr/local/bin PATH is honoured); the panel only renders state and posts
 * intent, exactly like the VSCode webview.
 */
class ManageAgentsPanel(private val project: Project) : JBPanel<ManageAgentsPanel>(), Disposable {

    private val app get() = ApplicationManager.getApplication()

    /** Per-command busy flag so a single agent can't be installed/updated twice at once. */
    private val busy = mutableSetOf<String>()

    private val statusLabel = JBLabel("").apply { foreground = UIUtil.getContextHelpForeground() }

    private val checkUpdatesBtn = JButton(message("manage.checkUpdates")).apply {
        addActionListener { checkUpdates() }
    }
    private val updateAllBtn = JButton(message("manage.updateAll")).apply {
        isEnabled = false
        addActionListener { updateAll() }
    }

    private val grid = JBPanel<JBPanel<*>>(WrapLayout(FlowLayout.LEFT, JBUI.scale(12), JBUI.scale(12))).apply {
        isOpaque = true
        background = UIUtil.getPanelBackground()
    }
    private val gridScroll = JBScrollPane(grid).apply {
        border = JBUI.Borders.empty()
        isOpaque = false
        viewport.isOpaque = false
    }

    /** Keep the whole grid live as background version checks publish results. */
    private val updateListener: (Map<String, AgentUpdateChecker.UpdateInfo>) -> Unit = {
        app.invokeLater { rebuild() }
    }

    init {
        layout = BorderLayout(0, JBUI.scale(8))
        border = JBUI.Borders.empty(8)

        val top = JBPanel<JBPanel<*>>(BorderLayout(JBUI.scale(8), 0)).apply {
            val title = JBLabel(message("manage.heading")).apply { font = font.deriveFont(font.size2D + 2f) }
            val actions = JBPanel<JBPanel<*>>(BorderLayout(JBUI.scale(6), 0)).apply {
                add(checkUpdatesBtn, BorderLayout.WEST)
                add(updateAllBtn, BorderLayout.CENTER)
            }
            add(title, BorderLayout.WEST)
            add(actions, BorderLayout.EAST)
        }

        add(top, BorderLayout.NORTH)
        add(gridScroll, BorderLayout.CENTER)
        add(statusLabel, BorderLayout.SOUTH)

        AgentUpdateChecker.addListener(updateListener)
        // Installed set can change from the Settings page (install there) or from this tab; rebuild on either.
        app.messageBus.connect(this)
            .subscribe(AgentExtenderSettings.DROPDOWN_REFRESH, object : AgentExtenderSettingsListener {
                override fun changed() = app.invokeLater { rebuild() }
            })
        app.messageBus.connect(this)
            .subscribe(AgentExtenderSettings.CHANGED, object : AgentExtenderSettingsListener {
                override fun changed() = app.invokeLater { rebuild() }
            })

        // Mirror VSCode's Agent Manager: seed cached versions (synchronous, inside checkAll) and kick off
        // the background scan *before* the first paint, so remembered versions show immediately instead of
        // flashing "-".
        if (!AgentUpdateChecker.isChecking()) AgentUpdateChecker.checkAll()
        rebuild()
    }

    override fun dispose() {
        AgentUpdateChecker.removeListener(updateListener)
    }

    // ── card model ──────────────────────────────────────────────────

    private data class Card(
        val id: String,
        val displayName: String,
        val command: String,
        val spec: InstallSpec?,
        val installed: Boolean,
        val status: AgentUpdateChecker.Status,
        val current: String,
        val latest: String,
        val url: String
    )

    private fun cardFor(def: AgentDef): Card {
        val cmd = def.command
        val installed = cmd.isNotBlank() && cmd.lowercase() in InstalledAgents.installed()
        val info = AgentUpdateChecker.infoFor(cmd)
        return Card(
            id = def.id,
            displayName = def.displayName,
            command = cmd,
            spec = def.install,
            installed = installed,
            status = info?.status ?: AgentUpdateChecker.Status.NA,
            current = info?.current ?: "",
            latest = info?.latest ?: "",
            url = def.url
        )
    }

    private fun busy(cmd: String): Boolean = cmd.isNotBlank() && cmd.lowercase() in busy

    /** An upgrade is possible when the spec has a package-manager/update command, or a site to open. */
    private fun canUpgrade(c: Card): Boolean =
        c.spec?.let { it.hasUpdateCommand || it.url.isNotBlank() } == true

    /** Install is offered for not-yet-installed automatable specs, or url specs (which open their page). */
    private fun canInstall(c: Card): Boolean {
        val spec = c.spec ?: return false
        return if (spec.type == "url") spec.url.isNotBlank()
        else spec.automatable && spec.target.isNotBlank() && !c.installed
    }

    // ── rendering ───────────────────────────────────────────────────

    private fun rebuild() {
        // Installed agents first, uninstalled last; within each group keep the registry's declared order
        // (sortedBy is stable). The installed set can change (install in this tab, background rescan), so
        // the sort is recomputed on every rebuild to stay consistent.
        val cards = AgentRegistry.agents.map { cardFor(it) }
            .sortedBy { if (it.installed) 0 else 1 }
        grid.removeAll()
        if (cards.isEmpty()) {
            grid.add(JBLabel(message("manage.empty")))
        } else {
            cards.forEach { grid.add(card(it)) }
        }
        val updatable = cards.count {
            it.status == AgentUpdateChecker.Status.UPDATE_AVAILABLE && canUpgrade(it) && !busy(it.command)
        }
        updateAllBtn.text = if (updatable > 0) message("manage.updateAllCount", updatable) else message("manage.updateAll")
        updateAllBtn.isEnabled = !AgentUpdateChecker.isChecking() && updatable > 0
        checkUpdatesBtn.isEnabled = !AgentUpdateChecker.isChecking()
        grid.revalidate()
        grid.repaint()
    }

    private fun card(c: Card): JComponent {
        val panel = JBPanel<JBPanel<*>>().apply {
            layout = BorderLayout(JBUI.scale(8), JBUI.scale(8))
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UIUtil.getBoundsColor()),
                JBUI.Borders.empty(10)
            )
            isOpaque = true
            background = UIUtil.getPanelBackground()
            preferredSize = JBUI.size(300, 200)
            minimumSize = JBUI.size(300, 200)
            maximumSize = JBUI.size(300, 200)
        }

        // head: icon + name (+ "update available" badge)
        val head = JBPanel<JBPanel<*>>(BorderLayout(JBUI.scale(8), 0)).apply {
            val icon = JBLabel(AgentIcons.forAgent(c.id, ""))
            val name = JBLabel(c.displayName).apply { font = font.deriveFont(font.size2D + 1f) }
            add(icon, BorderLayout.WEST)
            add(name, BorderLayout.CENTER)
        }
        if (c.status == AgentUpdateChecker.Status.UPDATE_AVAILABLE) {
            head.add(JBLabel(message("manage.updateAvailable")).apply {
                foreground = JBColor(0xCC8800, 0xE0B000)
            }, BorderLayout.EAST)
        }

        // version rows
        val rows = JBPanel<JBPanel<*>>(GridBagLayout()).apply { isOpaque = false }
        val gbc = GridBagConstraints().apply {
            anchor = GridBagConstraints.WEST
            insets = JBUI.insetsBottom(2)
        }
        fun row(label: String, value: String) {
            gbc.gridx = 0; gbc.weightx = 0.0
            rows.add(JBLabel(label).apply { foreground = UIUtil.getContextHelpForeground() }, gbc)
            gbc.gridx = 1; gbc.weightx = 1.0
            rows.add(JBLabel(value), gbc)
        }
        row(message("manage.currentVersion"), currentText(c))
        row(message("manage.latestVersion"), latestText(c))

        // action buttons
        val actions = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply { isOpaque = false }
        if (busy(c.command)) {
            actions.add(JButton(message("manage.working")).apply { isEnabled = false })
        } else {
            when {
                c.status == AgentUpdateChecker.Status.UPDATE_AVAILABLE && canUpgrade(c) ->
                    actions.add(actionBtn(message("manage.update"), true) { updateAgent(c) })
                !c.installed && canInstall(c) ->
                    actions.add(actionBtn(message("manage.install"), false) { installAgent(c, upgrade = false) })
                c.installed && canUpgrade(c) &&
                    c.status != AgentUpdateChecker.Status.CHECKING && c.status != AgentUpdateChecker.Status.UP_TO_DATE ->
                    actions.add(actionBtn(message("manage.update"), false) { updateAgent(c) })
            }
            // "Website" opens the agent's homepage (top-level url in agents.json). Fall back to the install
            // page (install.url) for url-type agents that only declare that. This mirrors the VSCode Agent
            // Manager, which shows the Website button for every agent that has a url.
            val websiteUrl = c.url.ifBlank { c.spec?.url ?: "" }
            if (websiteUrl.isNotBlank()) {
                actions.add(actionBtn(message("manage.website"), false) { BrowserUtil.browse(websiteUrl) })
            }
        }

        panel.add(head, BorderLayout.NORTH)
        panel.add(rows, BorderLayout.CENTER)
        panel.add(actions, BorderLayout.SOUTH)
        return panel
    }

    private fun currentText(c: Card): String =
        if (c.current.isNotBlank()) c.current else "-"

    private fun latestText(c: Card): String =
        if (c.latest.isNotBlank()) c.latest else "-"

    private fun actionBtn(label: String, primary: Boolean, onClick: () -> Unit): JButton =
        JButton(label).apply {
            if (primary) {
                // Emphasise the primary action (the available update) with the accent fill. The label
                // colour must contrast with the *accent* (the old code used the panel background, which
                // blended into the page and hid the text). Pick black/white by the accent's luminance so
                // it stays readable in both light and dark themes.
                val accent = UIUtil.getActiveTextColor() ?: JBColor(0x2D7BD4, 0x4A9BFF)
                background = accent
                foreground = if (ColorUtil.isDark(accent)) JBColor.WHITE else JBColor.BLACK
                isOpaque = true
            }
            addActionListener { onClick() }
        }

    // ── actions ────────────────────────────────────────────────────

    private fun checkUpdates() {
        if (AgentUpdateChecker.isChecking()) return
        setStatus(message("status.checkingUpdates"))
        AgentUpdateChecker.checkAll()
        app.executeOnPooledThread {
            while (AgentUpdateChecker.isChecking()) Thread.sleep(150)
            app.invokeLater {
                checkUpdatesBtn.isEnabled = true
                val avail = AgentUpdateChecker.snapshot().values.count {
                    it.status == AgentUpdateChecker.Status.UPDATE_AVAILABLE
                }
                if (avail > 0) setStatus(message("status.updatesAvailable", avail)) else setStatus("")
            }
        }
    }

    private fun updateAll() {
        if (AgentUpdateChecker.isChecking()) return
        val cmds = AgentRegistry.agents.mapNotNull { def ->
            val info = AgentUpdateChecker.infoFor(def.command)
            if (def.command.isNotBlank() && info?.status == AgentUpdateChecker.Status.UPDATE_AVAILABLE &&
                canUpgrade(cardFor(def))
            ) {
                def.command
            } else null
        }.distinct()
        cmds.forEach { cmd ->
            val def = AgentRegistry.byCommand(cmd) ?: return@forEach
            installAgent(cardFor(def), upgrade = true)
        }
    }

    private fun updateAgent(c: Card) = installAgent(c, upgrade = true)

    private fun installAgent(c: Card, upgrade: Boolean) {
        val spec = c.spec ?: return
        val cmd = c.command
        if (cmd.isBlank() || busy(cmd)) return
        if (spec.type == "url") {
            if (spec.url.isNotBlank()) BrowserUtil.browse(spec.url)
            return
        }
        if (!spec.automatable || spec.target.isBlank()) return

        busy.add(cmd.lowercase())
        rebuild()
        setStatus(message("status.installing", c.displayName, spec.target))
        app.executeOnPooledThread {
            val command = if (upgrade) AgentUpdateChecker.updateCommand(spec) else installShellCommand(spec)
            val (ok, lastLine) = AgentUpdateChecker.run(command)
            app.invokeLater {
                busy.remove(cmd.lowercase())
                if (!ok) {
                    setStatus(message("status.install.failed", c.displayName, lastLine), warn = true)
                    rebuild()
                    return@invokeLater
                }
                val verified = AgentDetector.canExecute(cmd)
                if (verified) {
                    // Incremental: add just this command to the installed cache (no full PATH re-scan),
                    // and let the live terminal dropdown + Settings page pick it up.
                    InstalledAgents.markInstalled(cmd)
                    AgentExtenderSettings.getInstance().fireDropdownRefresh()
                    setStatus(message("status.installed", c.displayName))
                    // After an upgrade, refresh this agent's cached update status so the badge clears.
                    if (upgrade) app.executeOnPooledThread { AgentUpdateChecker.refresh(cmd, spec) }
                } else {
                    setStatus(message("status.install.unverified", c.displayName, cmd), warn = true)
                }
                rebuild()
            }
        }
    }

    /** Build the install shell command for an [InstallSpec] (mirrors AgentExtenderConfigurable). */
    private fun installShellCommand(spec: InstallSpec): String = when (spec.type) {
        "npm" -> "npm install -g '${spec.pkg.replace("'", "'\\''")}'"
        "pip" -> "python3 -m pip install '${spec.pkg.replace("'", "'\\''")}'"
        "brew" -> "brew install '${spec.pkg.replace("'", "'\\''")}'"
        "shell" -> if (SystemInfo.isWindows && spec.cmdWin.isNotBlank()) spec.cmdWin else spec.cmd
        else -> ""
    }

    private fun setStatus(text: String, warn: Boolean = false) {
        statusLabel.text = text
        statusLabel.foreground = if (warn) UIUtil.getErrorForeground() else UIUtil.getContextHelpForeground()
    }
}
