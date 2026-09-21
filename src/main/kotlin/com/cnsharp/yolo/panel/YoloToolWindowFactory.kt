package com.cnsharp.yolo.panel

import com.cnsharp.yolo.Yolo
import com.cnsharp.yolo.YoloBundle.message
import com.cnsharp.yolo.YoloConstants
import com.cnsharp.yolo.launcher.ResumeAction
import com.cnsharp.yolo.launcher.SkipPermissionsAction
import com.cnsharp.yolo.settings.*
import com.cnsharp.yolo.terminal.AgentIcons
import com.cnsharp.yolo.terminal.YoloColorPalette
import com.cnsharp.yolo.terminal.YoloJediTermWidget
import com.cnsharp.yolo.util.baseName
import com.intellij.icons.AllIcons
import com.intellij.ide.IdeEventQueue
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowType
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.jediterm.core.util.TermSize
import com.jediterm.terminal.ProcessTtyConnector
import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.TextStyle
import com.jediterm.terminal.emulator.ColorPalette
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider
import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import com.pty4j.WinSize
import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.beans.PropertyChangeListener
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.*
import kotlin.math.max
import kotlin.math.min

/**
 * The standalone YOLO panel — a public ToolWindowFactory (registered in plugin.xml) that replicates the
 * Terminal's "AI Agents" dropdown experience without using any internal Terminal API.
 *
 * The panel shows a global "Skip permissions" toggle and a settings gear in its header, and an **agents
 * dropdown** (icon + name) that mirrors the Terminal's AI Agents selector. Clicking **Launch** opens the
 * selected agent in a new **tab** — a real PTY — inside the panel, so several agents can run side by side
 * like IDEA's built-in Terminal and the user switches between them with the tabs.
 *
 * The terminal is a third-party, fully public component (JediTerm + PTY4J, bundled with the IntelliJ
 * Platform), so no `@ApiStatus.Internal` / `@Experimental` Terminal API is touched and the plugin stays
 * Marketplace-safe.
 */
class YoloToolWindowFactory : ToolWindowFactory {

    private companion object {
        /** Default width (px) the panel seeds to on first open; capped to the IDE frame width. */
        const val PANEL_DEFAULT_WIDTH = 480
        /** Floor for the seeded initial width: never seed narrower than this even on very narrow windows. */
        const val PANEL_MIN_WIDTH = 360
    }

    override fun init(toolWindow: ToolWindow) {
        // Set the title as early as registration so the stripe never briefly shows the bare id "YOLO".
        // `title` is the tool-window header; `stripeTitle` is the button on the side stripe. Without the
        // latter the stripe button falls back to the `<toolWindow id="YOLO">` from plugin.xml.
        toolWindow.title = Yolo.NAME
        toolWindow.stripeTitle = Yolo.NAME
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // The product name has one source of truth (plugin.name via Yolo.NAME); derive the tool-window
        // title from it so renaming never requires touching plugin.xml. Set here (post-registration, always
        // called) and in init (registration time, avoids any first-paint of the bare id).
        toolWindow.title = Yolo.NAME
        // The side stripe button shows `stripeTitle`; without it the button falls back to the tool-window
        // id ("YOLO" in plugin.xml) and the panel looks unbranded even though the header is correct.
        toolWindow.stripeTitle = Yolo.NAME
        // Seed the panel's initial width AFTER the tool window is fully registered. Seeding from
        // `init()` crashes on 2026.2: ToolWindowManagerImpl.setToolWindowAnchor dereferences a null
        // internal descriptor during registration and throws "Cannot init toolwindow", which aborts
        // the whole tool-window registration. Doing it here (post-registration) keeps the panel coming
        // up and still applies the width to the first layout only.
        seedInitialWidth(toolWindow)
        val panel = YoloPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }

    /**
     * Seed the panel's *initial* width to ~38.2% of the IDE window (golden-ratio complement, leaving
     * ~61.8% for the editor). Applied via [ToolWindow.setDefaultState] so it only affects the first
     * layout; afterwards the user's own resize (persisted in workspace.xml) takes precedence.
     *
     * Best-effort only: a missed width is purely cosmetic, so guard the call. Never do this from
     * [ToolWindowFactory.init] — that runs mid-registration and ToolWindowManagerImpl.setToolWindowAnchor
     * dereferences a null internal descriptor and NPEs ("Cannot init toolwindow").
     */
    private fun seedInitialWidth(toolWindow: ToolWindow) {
        val ideFrame = WindowManager.getInstance().getIdeFrame(toolWindow.project) ?: return
        val ideWidth = ideFrame.component.width.takeIf { it > 0 } ?: return
        val width = max(PANEL_MIN_WIDTH, min(ideWidth, PANEL_DEFAULT_WIDTH))
        runCatching {
            toolWindow.setDefaultState(toolWindow.anchor, ToolWindowType.DOCKED, Rectangle(0, 0, width, ideFrame.component.height))
        }
    }
}

/** One entry in the agents dropdown. */
private data class AgentRow(
    val id: String,
    val displayName: String,
    val command: String,
    val baseArgs: String,
    val skipFlag: String,
    val resumeFlag: String,
    val iconPath: String
)

/**
 * Terminal settings for the embedded widget. Extends JediTerm's [DefaultSettingsProvider] and only
 * overrides the font: it reuses the IDE's own console font (the same one IDEA's Terminal uses, which
 * already renders Chinese correctly on this machine), so character cells line up AND CJK glyphs are not
 * clipped. Falls back to a logical monospace if the IDE font can't be resolved.
 */
private class YoloTerminalSettings : DefaultSettingsProvider() {
    private val consoleFont: Font = runCatching {
        val scheme = EditorColorsManager.getInstance().globalScheme
        Font(scheme.consoleFontName, Font.PLAIN, scheme.consoleFontSize)
    }.getOrElse { Font(Font.MONOSPACED, Font.PLAIN, 14) }

    override fun getTerminalFont(): Font = consoleFont
    override fun getTerminalFontSize(): Float = consoleFont.size.toFloat()

    /**
     * Use a 256-color-aware palette. JediTerm's default palette only resolves the first 16 ANSI
     * indices and asserts `index < 16` for any 256-color (SGR `38;5;n`) text; with assertions enabled
     * in the host JVM that throws from inside `paintComponent` and blanks the whole terminal. Our
     * palette resolves indices 16..255 via the standard xterm-256 formula instead.
     */
    override fun getTerminalColorPalette(): ColorPalette = YoloColorPalette()

    /**
     * The default hyperlink color is pure `java.awt.Color.BLUE` (0,0,255) — far too bright for the panel.
     * Use the user-configured color (Settings | Tools | YOLO), falling back to the muted steel-blue default.
     */
    override fun getHyperlinkColor(): TextStyle {
        val rgb = runCatching { AgentExtenderSettings.getInstance().state.linkColorRgb }
            .getOrDefault(AgentExtenderSettings.DEFAULT_LINK_COLOR_RGB)
        return TextStyle(TerminalColor(rgb), null)
    }
}

private class YoloPanel(
    private val project: Project
) : JBPanel<YoloPanel>(), Disposable {

    private companion object {
        /** CardLayout key for [contentArea]: the empty placeholder shown before any terminal is launched. */
        const val CARD_EMPTY = "empty"
    }

    private val LOG = Logger.getInstance(YoloPanel::class.java)

    private val agentCombo = ComboBox<AgentRow>().apply {
        renderer = object : SimpleListCellRenderer<AgentRow>() {
            override fun customize(list: JList<out AgentRow>, value: AgentRow?, index: Int, selected: Boolean, hasFocus: Boolean) {
                if (value != null) {
                    // The "AI Agents" prompt item carries no command, so it shows no agent icon.
                    icon = if (value.command.isBlank()) null else AgentIcons.forAgent(value.id, value.iconPath)
                    text = value.displayName
                }
            }
        }
    }

    /** Placeholder shown until the first agent is launched (mirrors IDEA's empty Terminal). */
    private val emptyPlaceholder = JBLabel(message("panel.emptyHint")).apply {
        horizontalAlignment = SwingConstants.CENTER
    }

    /** Each launched agent gets its own card in [contentArea]; [tabBar] shows a clickable header per tab. */
    private val cardLayout = CardLayout()

    /** Hosts the empty hint or the currently selected terminal widget (one card per session). */
    private val contentArea = JBPanel<JBPanel<*>>(cardLayout).apply {
        add(emptyPlaceholder, CARD_EMPTY)
    }

    /** The clickable tab strip above [contentArea]; one entry per open session. Laid out left-to-right
     *  with [BoxLayout] (never wraps) so a horizontal scrollbar appears in [tabBarScroll] once tabs overflow. */
    private val tabBar = JBPanel<JBPanel<*>>().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = true
        background = UIUtil.getPanelBackground()
        border = JBUI.Borders.customLine(UIUtil.getBoundsColor(), 0, 0, 1, 0)
    }

    /** Horizontal-only scroll wrapper for [tabBar] so many open agents stay reachable. */
    private val tabBarScroll = JBScrollPane(
        tabBar,
        ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER,
        ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
    ).apply {
        border = JBUI.Borders.empty()
        isOpaque = false
        viewport.isOpaque = false
    }

    /**
     * Dropdown arrow to the right of the settings gear: lists every open terminal for quick switching.
     * Stays hidden until a *second* tab is open — with zero or one tab there is nothing to switch to,
     * so the arrow would be a dead control.
     */
    private val tabDropdownBtn = JButton(AllIcons.General.ChevronDown).apply {
        isBorderPainted = false
        isContentAreaFilled = false
        isFocusable = false
        isVisible = false
        toolTipText = message("panel.tabDropdown")
        addActionListener { showTabDropdown() }
    }

    /**
     * Vertical split: tab strip on top, terminal cards below. Replaces the Swing JTabbedPane, whose custom
     * tab components are NOT painted by IntelliJ's DarculaTabbedPaneUI — so tabs came up with no visible
     * title, border, or close button. The hand-built strip is fully theme-aware and always renders.
     */
    private val terminalSplit = JBPanel<JBPanel<*>>(BorderLayout(0, JBUI.scale(4))).apply {
        add(tabBarScroll, BorderLayout.NORTH)
        add(contentArea, BorderLayout.CENTER)
    }

    /** All currently open terminal sessions, in tab order. */
    private val sessions = mutableListOf<Session>()

    /** Index of the selected session in [sessions], or -1 when none is open. */
    private var selectedIndex = -1

    /** Monotonic id source for per-session card keys (stable across closes, unlike list indices). */
    private var sessionSeq = 0

    /** The session shown in the currently selected tab. */
    private fun activeSession(): Session? {
        return if (selectedIndex in sessions.indices) sessions[selectedIndex] else null
    }

    /** The terminal widget of the currently selected tab (drives scale + Ctrl+C targeting). */
    private fun activeWidget(): YoloJediTermWidget? = activeSession()?.widget

    /** One open terminal: its widget, its PTY process, the agent row, its tab header, and its card key. */
    private data class Session(
        val widget: YoloJediTermWidget,
        val process: Process,
        val row: AgentRow,
        val tabComp: JComponent,
        val cardKey: String
    )

    /**
     * Guards against stale asynchronous refresh results. Each [rebuild] bumps the generation; only the
     * callback carrying the latest generation is applied. This prevents a slow PATH probe from an earlier
     * rebuild from overwriting a newer one (which could otherwise re-add items and duplicate the list).
     */
    private val refreshGeneration = AtomicInteger(0)

    /**
     * Fired when the OS display scale changes (DPI / monitor switch). A scale change does not always
     * change the component's pixel size, so JediTerm never recomputes its grid and its cached image goes
     * stale — leaving ghost artifacts. We force a recompute ourselves.
     */
    private val scaleChangeListener = PropertyChangeListener { activeWidget()?.forceReinitFull() }

    /**
     * Intercepts a small set of keystrokes while the embedded terminal has focus and delivers them straight
     * to JediTerm, so they reach the PTY / the agent's own TUI instead of being swallowed by IDEA's global
     * shortcuts:
     *   - Ctrl+C  → SIGINT (or Copy, when there is a selection)
     *   - Ctrl+T  → show / toggle the agent's todo list
     *   - Esc     → interrupt / cancel the agent's current action
     *
     * Keys that don't collide with an IDEA shortcut (e.g. Ctrl+O) reach the terminal on their own and are
     * deliberately left out of this list — the dispatcher exists only to win back keys IDEA would otherwise
     * swallow.
     *
     * This MUST run ahead of IDEA's own keystroke processing. A plain [java.awt.Toolkit] AWT listener does
     * NOT work: IDEA's [com.intellij.ide.IdeEventQueue] processes shortcuts at the head of the event queue,
     * before Toolkit listeners fire, so the "Shortcuts conflicts" dialog would already be on screen by the
     * time a Toolkit listener could consume the event. Registering an [com.intellij.ide.IdeEventQueue.EventDispatcher]
     * instead lets us intercept the event first and return `true` to stop IDEA from ever treating it as a
     * shortcut — no conflict dialog. We then deliver the key straight to JediTerm, which applies its own
     * selection-aware / agent-defined behavior.
     *
     * Only the real terminal panel is affected — the dropdown, toolbar and the rest of the IDE keep their
     * normal behavior. ⌘-based shortcuts are left untouched because the specs below require a plain Ctrl
     * modifier (or no modifier, for Esc).
     */
    // Implement the base `IdeEventQueue.EventDispatcher` (not `NonLockedEventDispatcher`): the latter was
    // introduced after our since-build 233, so the plugin verifier reports it as an unresolved class on
    // 2023.3. The base interface has existed since long before 233 and exposes the same `dispatch` hook.
    // The only behavioural difference is that `NonLockedEventDispatcher` skips invocation while the event
    // queue is locked (modal dialogs / write actions); for terminal key interception that's irrelevant.
    private data class TerminalKeySpec(val modifiersEx: Int, val keyCode: Int, val keyChar: Char)

    private val terminalKeySpecs = listOf(
        TerminalKeySpec(InputEvent.CTRL_DOWN_MASK, KeyEvent.VK_C, 'c'),
        TerminalKeySpec(InputEvent.CTRL_DOWN_MASK, KeyEvent.VK_T, 't'),
        TerminalKeySpec(0, KeyEvent.VK_ESCAPE, KeyEvent.VK_ESCAPE.toChar())
    )

    private val terminalKeyDispatcher = object : IdeEventQueue.EventDispatcher {
        override fun dispatch(e: AWTEvent): Boolean {
            if (e !is KeyEvent || e.id != KeyEvent.KEY_PRESSED) return false
            // Bare keystroke only — no Shift/Alt/Meta beyond what the spec itself requires.
            val mods = e.modifiersEx and
                (InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK or InputEvent.ALT_DOWN_MASK or InputEvent.META_DOWN_MASK)
            val spec = terminalKeySpecs.firstOrNull { it.modifiersEx == mods && it.keyCode == e.keyCode }
                ?: return false

            val panel = activeWidget()?.getTerminalPanel() ?: return false
            val focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner ?: return false
            if (!SwingUtilities.isDescendingFrom(focus, panel)) return false

            // Consume before IDEA's action system sees it, then deliver the key to JediTerm ourselves.
            e.consume()
            val forward = KeyEvent(
                panel, KeyEvent.KEY_PRESSED, System.currentTimeMillis(),
                spec.modifiersEx, spec.keyCode, spec.keyChar
            )
            panel.processKeyEvent(forward)
            return true
        }
    }

    init {
        layout = BorderLayout(0, JBUI.scale(6))
        border = JBUI.Borders.empty(8)

        val group = DefaultActionGroup().apply {
            add(SkipPermissionsAction())
            add(ResumeAction())
            add(OpenSettingsAction())
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("YOLO.Toolbar", group, true)
        toolbar.targetComponent = this

        // Launch starts the currently selected agent; the dropdown only selects (no auto-launch), so the
        // Skip/Resume toggles are always applied on the next Launch and a mis-click on the dropdown never
        // kills a running terminal.
        val launchBtn = JButton(message("panel.launch")).apply {
            addActionListener { launchSelected() }
        }

        val header = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            val selector = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
                add(agentCombo, BorderLayout.CENTER)
                add(launchBtn, BorderLayout.EAST)
            }
            add(selector, BorderLayout.CENTER)
            val right = JPanel(BorderLayout(JBUI.scale(2), 0)).apply {
                add(toolbar.component, BorderLayout.CENTER)
                add(tabDropdownBtn, BorderLayout.EAST)
            }
            add(right, BorderLayout.EAST)
        }

        // Start on the placeholder card; the first Launch adds a real terminal card.
        cardLayout.show(contentArea, CARD_EMPTY)

        add(header, BorderLayout.NORTH)
        add(terminalSplit, BorderLayout.CENTER)

        // Track OS display-scale changes so the embedded terminal can recompute (see scaleChangeListener).
        Toolkit.getDefaultToolkit().addPropertyChangeListener("awt.font.desktophints", scaleChangeListener)

        // Keep Ctrl+C / Ctrl+T / Esc for the embedded terminal instead of letting IDEA swallow them —
        // see terminalKeyDispatcher. A Dispatcher intercepts the keystroke at the head of IDEA's event
        // queue, ahead of its shortcut processing, which a Toolkit AWT listener cannot do (the conflict
        // dialog would already be shown).
        IdeEventQueue.getInstance().addDispatcher(terminalKeyDispatcher, this)

        // Refresh the dropdown when the user applies changes in Settings | Tools | YOLO (e.g. base-args edits),
        // so an already-open panel picks up the new values instead of keeping its initial rows.
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(AgentExtenderSettings.CHANGED, object : AgentExtenderSettingsListener {
                override fun changed() = rebuild()
            })
        // Lightweight refresh after an install: re-read the installed-agents cache and rebuild the dropdown
        // WITHOUT re-scanning PATH — only the one newly installed agent should appear.
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(AgentExtenderSettings.DROPDOWN_REFRESH, object : AgentExtenderSettingsListener {
                override fun changed() = populateFromCache(buildRows())
            })

        rebuild()
    }

    /** Rebuild the agents dropdown from current settings. */
    private fun rebuild() {
        val rows = buildRows()
        // Fast path: show the persisted installed set immediately (no PATH probes) so the dropdown is instant.
        populateFromCache(rows)
        // Slow path: re-scan installed agents in the background; only rebuild the dropdown if the set changed.
        rescanInstalled(rows)
    }

    /** Populate the dropdown from the persisted installed-agents cache (instant, no PATH probes). */
    private fun populateFromCache(rows: List<AgentRow>) {
        val installed = InstalledAgents.installed()
        val visible = rows.filter { it.command.isBlank() || installed.contains(it.command.lowercase()) }
        agentCombo.removeAllItems()
        visible.forEach { agentCombo.addItem(it) }
        selectDefaultAgent(visible)
    }

    /** Default the dropdown to the last launched agent (if still visible), otherwise to the "AI Agents" prompt. */
    private fun selectDefaultAgent(visible: List<AgentRow>) {
        val last = AgentExtenderSettings.getInstance().state.lastAgentId
        val idx = if (last.isNotBlank()) visible.indexOfFirst { it.id == last } else -1
        agentCombo.selectedIndex = if (idx in visible.indices) idx else 0
    }

    /** Re-scan installed agents on a background thread; update the cache and the dropdown only if it differs. */
    private fun rescanInstalled(rows: List<AgentRow>) {
        val gen = refreshGeneration.incrementAndGet()
        InstalledAgents.rescan(rows.map { it.command }) {
            // Ignore this callback if a newer rebuild has started in the meantime.
            if (gen == refreshGeneration.get()) populateFromCache(rows)
        }
    }

    /** Build the full row set, deduplicated by command so a custom tool sharing a promoted agent's command is not listed twice. */
    private fun buildRows(): List<AgentRow> {
        val settings = AgentExtenderSettings.getInstance().state
        val ruleByCmd = settings.permissionRules.associateBy({ baseName(it.agentId).lowercase() }, { it.flag })
        val resumeRuleByCmd = settings.resumeRules.associateBy({ baseName(it.agentId).lowercase() }, { it.flag })

        fun flagFor(cmd: String, id: String): String {
            val saved = ruleByCmd[baseName(cmd).lowercase()]
            if (!saved.isNullOrBlank()) return saved
            return AgentRegistry.skipFlagFor(baseName(cmd)).ifBlank { AgentRegistry.skipFlagFor(id) }
        }

        fun resumeFor(cmd: String, id: String): String {
            val saved = resumeRuleByCmd[baseName(cmd).lowercase()]
            if (!saved.isNullOrBlank()) return saved
            return AgentRegistry.resumeFlagFor(baseName(cmd)).ifBlank { AgentRegistry.resumeFlagFor(id) }
        }

        // Collect into a list, but skip any row whose (non-blank) command was already seen — promoted agents
        // are added before custom tools, so a custom tool duplicating a promoted command is silently dropped.
        val rows = mutableListOf<AgentRow>()
        val seenCommands = mutableSetOf<String>()
        fun addUnique(row: AgentRow) {
            val key = row.command.lowercase().trim()
            if (key.isBlank() || seenCommands.add(key)) rows += row
        }

        // The "AI Agents" prompt is the default selection (shown as the dropdown title). It is not a
        // real agent, so it launches nothing — this also prevents the first real agent from auto-launching
        // when the panel opens and selectedIndex is set programmatically.
        addUnique(AgentRow("", message("panel.agentsPrompt"), "", "", "", "", ""))
        for (def in AgentRegistry.agents) {
            val baseArgs = settings.agentBaseArgs[def.id.lowercase()] ?: ""
            addUnique(
                AgentRow(
                    def.id, def.displayName, def.command, baseArgs,
                    flagFor(def.command, def.id), resumeFor(def.command, def.id), ""
                )
            )
        }
        for (tool in settings.customTools) {
            addUnique(
                AgentRow(
                    id = tool.id,
                    displayName = tool.displayName.ifBlank { tool.id },
                    command = tool.command,
                    baseArgs = tool.baseArgs,
                    skipFlag = flagFor(tool.command, tool.id),
                    resumeFlag = resumeFor(tool.command, tool.id),
                    iconPath = tool.iconPath
                )
            )
        }
        return rows
    }

    private fun launchSelected() {
        val row = agentCombo.selectedItem as? AgentRow ?: return
        // The default "AI Agents" prompt item has a blank command — never launch it.
        if (row.command.isBlank()) return
        maybeWarnAndLaunch(row)
    }

    /**
     * Launch gate for "Skip permissions" (YOLO mode): warn once before the agent runs with no confirmation.
     * The agent can edit files / run commands unattended, so the user must opt in each launch — unless they
     * have ticked "Don't show again" (persisted to [AgentExtenderSettings.State.skipWarningDismissed]), in
     * which case the warning is suppressed.
     */
    private fun maybeWarnAndLaunch(row: AgentRow) {
        val settings = AgentExtenderSettings.getInstance().state
        val envPair = AgentRegistry.skipEnvFor(baseName(row.command))
        // Only warn when the skip flag actually takes effect (matches the injection guard in [launch]).
        val skipActive = settings.skipEnabled && (row.skipFlag.isNotBlank() || envPair != null)
        if (!skipActive || settings.skipWarningDismissed) {
            launch(row)
            return
        }

        var doNotAskAgain = false
        // Type as DialogWrapper.DoNotAskOption (not the bare com.intellij.openapi.ui.DoNotAskOption):
        // the bare-DoNotAskOption overloads of Messages.showOkCancelDialog were removed by 2023.3/2025.2,
        // but the DialogWrapper.DoNotAskOption overloads exist continuously from 2023.3 through 2026, so
        // this keeps the warning dialog binary-compatible across the whole supported range.
        val doNotAsk = object : com.intellij.openapi.ui.DialogWrapper.DoNotAskOption {
            override fun isToBeShown(): Boolean = true
            override fun setToBeShown(toBeShown: Boolean, exitCode: Int) {
                // Checkbox checked ⇒ "don't show again" ⇒ toBeShown is false.
                doNotAskAgain = !toBeShown
            }
            override fun canBeHidden(): Boolean = true
            override fun shouldSaveOptionsOnCancel(): Boolean = false
            override fun getDoNotShowMessage(): String = message("warning.skip.doNotShow")
        }
        val result = Messages.showOkCancelDialog(
            project,
            message("warning.skip.body"),
            message("warning.skip.title"),
            message("button.launchAnyway"),
            Messages.getCancelButton(),
            Messages.getWarningIcon(),
            doNotAsk
        )
        // Cancel (or dialog closed) ⇒ abort the launch entirely.
        if (result != Messages.OK) return
        if (doNotAskAgain) settings.skipWarningDismissed = true
        launch(row)
    }

    /** Launch the selected agent into an interactive terminal embedded in the panel. */
    private fun launch(row: AgentRow) {
        // Remember this agent so the panel re-selects it on next open (only once the launch is actually happening).
        AgentExtenderSettings.getInstance().state.lastAgentId = row.id
        val settings = AgentExtenderSettings.getInstance().state

        val cmd = mutableListOf(row.command)
        row.baseArgs.split(' ').filter { it.isNotBlank() }.let { cmd += it }

        // Inherit the OS environment and inject any env-based bypass (e.g. goose's GOOSE_MODE).
        val env = HashMap(System.getenv())
        // The IDE is a GUI app launched without a terminal, so its environment usually lacks TERM /
        // COLORTERM. TUIs key their colour support off these (e.g. claude needs COLORTERM=truecolor for
        // its truecolour logo); without them the agent renders monochrome. Set sensible defaults so the
        // spawned PTY child emits colour just like a normal interactive terminal would.
        env.putIfAbsent("TERM", "xterm-256color")
        env["COLORTERM"] = "truecolor"
        env.putIfAbsent("CLICOLOR", "1")
        val envPair = AgentRegistry.skipEnvFor(baseName(row.command))
        if (settings.skipEnabled && (row.skipFlag.isNotBlank() || envPair != null)) {
            // The flag may be multiple tokens (e.g. cline's "--auto-approve true"), so it must be split into
            // separate argv entries; otherwise it becomes one space-containing argument that cannot be parsed.
            if (row.skipFlag.isNotBlank()) {
                val tokens = row.skipFlag.split(' ').filter { it.isNotBlank() }
                if (tokens.isNotEmpty() && tokens[0] !in cmd) cmd += tokens
            }
            envPair?.let { (name, value) -> env[name] = value }
        }

        // LLM provider injection (2.0): if this agent is bound to a user-defined provider, point it at that
        // backend. Env-based agents (claude/goose/aider/gemini) get spawn-env overrides; config-file agents
        // (codex/opencode/codebuddy) get their config file written/merged and the api-key env var injected.
        // In both cases the api key is resolved from PasswordSafe and never appears in plaintext here.
        val boundProviderId = settings.providerBindings[row.id.lowercase()]
        if (!boundProviderId.isNullOrBlank()) {
            val provider = settings.providers.find { it.id == boundProviderId }
            if (provider != null) {
                if (AgentConfigInjector.configBased(row.id)) {
                    for ((name, value) in AgentConfigInjector.applyConfig(provider, row.id.lowercase())) {
                        env[name] = value
                    }
                } else {
                    for ((name, value) in LlmProviderSupport.toEnv(provider, row.id.lowercase())) {
                        env[name] = value
                    }
                }
            }
        }

        // When "Resume session" is on, append the agent's resume flag (e.g. -r / --resume) so the agent
        // continues a previous session. Agents with no resumeFlag are launched unchanged.
        if (settings.resumeEnabled && row.resumeFlag.isNotBlank()) {
            val tokens = row.resumeFlag.split(' ').filter { it.isNotBlank() }
            if (tokens.isNotEmpty() && tokens[0] !in cmd) cmd += tokens
        }

        val shellCmd = cmd.joinToString(" ")
        val dir = project.basePath ?: System.getProperty("user.home")
        // Run through an interactive login shell so the agent sees the user's rc-defined PATH (nvm/fnm/…).
        val ptyCommand = if (SystemInfo.isWindows) arrayOf("cmd", "/c", shellCmd)
        else arrayOf("zsh", "-lic", shellCmd)

        LOG.info("${Yolo.NAME}: starting agent=${row.displayName}, command=$shellCmd")

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val process = PtyProcessBuilder(ptyCommand)
                    .setDirectory(dir)
                    .setEnvironment(env)
                    .setRedirectErrorStream(true)
                    .start()
                // Tracks what the user types so links are never painted inside the agent's input box.
                // Declared here because the tty connector below feeds it and the filters (added later,
                // once the widget exists) read it.
                val typedInput = TypedInputGuard()
                val connector = object : ProcessTtyConnector(process, StandardCharsets.UTF_8) {
                    override fun getName(): String = YoloConstants.ID

                    /**
                     * Everything the user types reaches the agent through here — the one place where our
                     * input can be told apart from the agent's output. A PTY returns one undifferentiated
                     * stream, so the echo of a keystroke is indistinguishable from agent output by the
                     * time it is painted; recording it here lets [TypedInputGuard] keep the text being
                     * typed free of hyperlinks (see [InputAwareLinkFilter]).
                     *
                     * [ProcessTtyConnector.write] (the String overload) delegates to this method, so
                     * overriding only this one records each keystroke exactly once.
                     */
                    override fun write(bytes: ByteArray) {
                        typedInput.onUserInput(String(bytes, StandardCharsets.UTF_8))
                        super.write(bytes)
                    }

                    /**
                     * Push the terminal size to the PTY so the child process (and any interactive TUI it
                     * spawns) learns the real grid dimensions. JediTerm's [ProcessTtyConnector] does NOT
                     * override `resize` — its default is a no-op — so without this the PTY winsize stays at
                     * the OS default (80x24) and is never updated when the panel is resized. The TUI then
                     * redraws against that stale size: clearing/rewriting lines with wrong cursor math
                     * produces the ghosting and duplicate lines seen when moving the selection with arrow
                     * keys. pty4j's [PtyProcess.setWinSize] updates the PTY and raises SIGWINCH, so the TUI
                     * re-queries and re-renders at the correct width/height.
                     */
                    override fun resize(termSize: TermSize) {
                        val p = getProcess()
                        if (p is PtyProcess) runCatching { p.setWinSize(WinSize(termSize.columns, termSize.rows)) }
                    }
                }
                ApplicationManager.getApplication().invokeLater {
                    try {
                        val widget = YoloJediTermWidget(YoloTerminalSettings())
                        // Hard-wrap state shared by all reference filters so they can reconstruct references
                        // the terminal split across physical lines, and suppress duplicate / false links.
                        val wrapState = WrapState()
                        // File references (path[:line[:col]], ranges, ~/, file://, quoted paths with spaces).
                        widget.addHyperlinkFilter(InputAwareLinkFilter(FileLinkFilter(project, dir, wrapState), typedInput))
                        // Class.member / Class#member → the specific method/field/inner class. Runs before the
                        // stack-trace filter so a reconstructed continuation tail (via continuationSpan) suppresses
                        // any stray bare-name match (e.g. a `.pl` extension) that would otherwise overlap it.
                        widget.addHyperlinkFilter(InputAwareLinkFilter(MemberLinkFilter(project, wrapState), typedInput))
                        // Type references (qualified names and project simple names) → class declaration.
                        widget.addHyperlinkFilter(InputAwareLinkFilter(TypeLinkFilter(project, wrapState = wrapState), typedInput))
                        // Stack-trace frames / tracebacks where only the file name is printed (Bar.java:123, File "x", line N).
                        widget.addHyperlinkFilter(
                            InputAwareLinkFilter(StackTraceLinkFilter(project, dir, wrapState), typedInput)
                        )
                        // http(s):// URLs → system browser (does not hide the pane).
                        widget.addHyperlinkFilter(InputAwareLinkFilter(UrlLinkFilter(), typedInput))
                        widget.setTtyConnector(connector)
                        installFileDrop(widget)
                        // Warm the project-type cache off the terminal thread so the first streamed line
                        // doesn't stall while the snapshot is built.
                        if (!DumbService.isDumb(project)) {
                            ApplicationManager.getApplication().executeOnPooledThread {
                                runCatching { YoloProjectTypes.snapshot(project) }
                            }
                        }
                        // Add to a laid-out container and force a grid recompute first, then start — so the
                        // terminal's character grid is sized to the real component and a scale change later
                        // triggers a clean recompute (no ghost artifacts).
                        addTerminalSession(widget, process, row)
                        widget.start()
                        // Hand keyboard focus to the terminal so the caret lands in the agent's
                        // panel (user can type immediately) and Ctrl+C is delivered to the terminal
                        // as SIGINT instead of being intercepted by IDEA's global Copy shortcut.
                        SwingUtilities.invokeLater { widget.getTerminalPanel().requestFocusInWindow() }
                    } catch (e: Exception) {
                        LOG.warn("${Yolo.NAME}: failed to embed terminal for ${row.displayName}", e)
                    }
                }
            } catch (e: Exception) {
                LOG.warn("${Yolo.NAME}: failed to start agent ${row.displayName}", e)
            }
        }
    }

    /**
     * Open a fresh terminal tab for the just-launched agent. Each Launch adds a new tab (it never
     * replaces an existing terminal), so several agents can run side by side and the user switches
     * between them like IDEA's built-in Terminal tabs.
     */
    private fun addTerminalSession(widget: YoloJediTermWidget, process: Process, row: AgentRow) {
        val cardKey = "session-${sessionSeq++}"
        contentArea.add(widget, cardKey)
        val tabComp = buildTabComponent(row, widget)
        tabBar.add(tabComp)
        sessions.add(Session(widget, process, row, tabComp, cardKey))
        selectedIndex = sessions.lastIndex
        cardLayout.show(contentArea, cardKey)
        updateTabSelection()
        updateTabDropdownVisibility()

        // JediTerm's own TerminalPanel handles ongoing resizes itself: its built-in `componentResized`
        // listener recomputes the grid (sizeTerminalFromComponent) without re-deriving the font — so text
        // stays sharp on HiDPI — and recreates the backing image via postResize, so no ghost glyphs.
        //
        // The only thing we drive manually is the *initial* grid, which YoloTerminalPanel recomputes once
        // the first time it reaches a real (non-zero) size. We add to a laid-out card first so the
        // widget has real dimensions to size against, then start.
        tabBarScroll.revalidate()
        tabBarScroll.repaint()
        contentArea.revalidate()
        SwingUtilities.invokeLater { widget.getTerminalPanel().requestFocusInWindow() }
    }

    /**
     * Enables dragging files (from the OS file manager or the IDE's Project view) onto the terminal: their
     * paths are typed into the agent's input line. The path string is fed straight to the PTY through the
     * terminal's TTY connector — the exact same path every keystroke takes — so the agent sees precisely
     * what a user would have typed. Any pre-existing [TransferHandler] (e.g. JediTerm's paste) is preserved
     * by delegating non-file flavors to it.
     */
    private fun installFileDrop(widget: YoloJediTermWidget) {
        val delegate = widget.transferHandler
        widget.transferHandler = object : TransferHandler() {
            override fun canImport(support: TransferHandler.TransferSupport): Boolean =
                support.isDataFlavorSupported(DataFlavor.javaFileListFlavor) ||
                    virtualFileFlavor(support.transferable) != null ||
                    (delegate?.canImport(support) ?: false)

            override fun importData(support: TransferHandler.TransferSupport): Boolean {
                if (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor) ||
                    virtualFileFlavor(support.transferable) != null
                ) {
                    val text = extractDroppedPaths(support.transferable)
                        .takeIf { it.isNotEmpty() }
                        ?.joinToString(" ") { quotePath(it) }
                    if (text != null) {
                        runCatching { widget.ttyConnector?.write(text) }
                            .onFailure { LOG.warn("${Yolo.NAME}: failed to insert dropped file path", it) }
                    }
                    return true
                }
                return delegate?.importData(support) ?: false
            }
        }
    }

    /**
     * The IDE's Project-view drag advertises a `VirtualFile` (array) flavor. Its holder class
     * (`com.intellij.ide.DataFlavors`) is not on the plugin's compile classpath, so we match the flavor by
     * its representation class instead of referencing the constant.
     */
    private fun virtualFileFlavor(transferable: Transferable): DataFlavor? =
        transferable.transferDataFlavors.firstOrNull { flavor ->
            val rc = runCatching { flavor.representationClass }.getOrNull()
            rc == VirtualFile::class.java || rc == Array<VirtualFile>::class.java
        }

    /** Pull file paths from a drop, accepting both OS file drags and IDE-internal VirtualFile drags. */
    private fun extractDroppedPaths(transferable: Transferable): List<String> {
        runCatching {
            (transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)
                ?.filterIsInstance<File>()?.map { it.absolutePath }
        }.getOrNull()?.let { if (it.isNotEmpty()) return it }

        virtualFileFlavor(transferable)?.let { flavor ->
            runCatching { transferable.getTransferData(flavor) }
                .getOrNull()
                ?.let { data ->
                    val paths = when (data) {
                        is Array<*> -> data.filterIsInstance<VirtualFile>().map { it.path }
                        is List<*> -> data.filterIsInstance<VirtualFile>().map { it.path }
                        else -> emptyList()
                    }
                    if (paths.isNotEmpty()) return paths
                }
        }

        return emptyList()
    }

    /** Quote a path that contains whitespace so the agent's input treats it as one argument. */
    private fun quotePath(path: String): String =
        if (path.any { it.isWhitespace() }) "\"$path\"" else path

    /** Repaint every tab header so the selected one is highlighted and the rest are flat. */
    private fun updateTabSelection() {
        for (i in sessions.indices) {
            val tab = sessions[i].tabComp
            // Drive the highlight through [TabHeader.selected] (painted every repaint) rather than a one-shot
            // `background` assignment, so it survives a tool-window hide/show or LaF refresh that would
            // otherwise reset the background to the panel colour.
            if (tab is TabHeader) {
                tab.selected = (i == selectedIndex)
                tab.background = tab.backgroundForState
            }
            tab.repaint()
        }
        activeWidget()?.forceReinitFull()
    }

    /**
     * Show the "Switch terminal" arrow only while **more than one** terminal tab is open. With a single
     * tab there is nothing to switch to (the menu would list just the tab you are already on), so the
     * arrow would be a dead control next to the placeholder. See [showTabDropdown], which bails out
     * unless it has at least two tabs to choose between.
     */
    private fun updateTabDropdownVisibility() {
        val shouldShow = sessions.size > 1
        if (tabDropdownBtn.isVisible == shouldShow) return
        tabDropdownBtn.isVisible = shouldShow
        // Relayout the button's parent so the header reflows instead of leaving a gap.
        tabDropdownBtn.parent?.revalidate()
        tabDropdownBtn.parent?.repaint()
    }

    /** Select the session at [index] (switch the visible card + highlight its header). */
    private fun selectTab(index: Int) {
        if (index !in sessions.indices) return
        selectedIndex = index
        cardLayout.show(contentArea, sessions[index].cardKey)
        updateTabSelection()
    }

    /** Select the session whose widget matches [widget] (tab headers are matched by widget, which is stable). */
    private fun selectByWidget(widget: YoloJediTermWidget) {
        val i = sessions.indexOfFirst { it.widget == widget }
        if (i >= 0) selectTab(i)
    }

    /**
     * One entry of the hand-built tab strip. Unlike a Swing JTabbedPane tab, this component paints its own
     * background, and it derives the *selected* highlight from [selected] on **every** paint — not once at
     * selection time. That keeps the highlight correct across LaF refreshes and tool-window show/hide, which
     * otherwise re-install the component UI and reset an imperatively-set `background` to the panel colour,
     * leaving the selected tab looking unselected until the next selection change.
     */
    private class TabHeader : JBPanel<TabHeader>() {
        var selected = false
        /** Selection-aware background: list-selection colour when selected, otherwise the panel colour. */
        val backgroundForState: Color
            get() = if (selected) UIUtil.getListBackground(true) else UIUtil.getPanelBackground()
        override fun paintComponent(g: Graphics?) {
            super.paintComponent(g)
            g ?: return
            g.color = backgroundForState
            g.fillRect(0, 0, width, height)
        }
    }

    /** Build one entry of the tab strip: agent icon + name + a close (✕) button that tears down that session. */
    private fun buildTabComponent(row: AgentRow, widget: YoloJediTermWidget): JComponent {
        val icon = if (row.command.isBlank()) null else AgentIcons.forAgent(row.id, row.iconPath)
        val nameLabel = JBLabel(row.displayName, icon, SwingConstants.LEFT).apply {
            // Pin an explicit, theme-aware foreground so the name is always readable regardless of LaF.
            foreground = UIUtil.getLabelForeground()
        }
        val closeBtn = JButton("✕").apply {
            isBorderPainted = false
            isContentAreaFilled = false
            isFocusable = false
            // Drop the default JButton margin so the ✕ glyph sits flush to the tab's right edge
            // instead of leaving a few px of internal padding on its right.
            margin = JBUI.emptyInsets()
            // Right-align the glyph within the button so it hugs the tab's right edge (border inset 0).
            horizontalAlignment = SwingConstants.RIGHT
            foreground = UIUtil.getLabelForeground()
            toolTipText = message("panel.closeTab")
            font = JBUI.Fonts.smallFont()
            addActionListener {
                // Look the session up by widget at click time: indices shift when an earlier tab is
                // closed, but the widget reference is stable, so we always find the right tab.
                val i = sessions.indexOfFirst { it.widget == widget }
                if (i >= 0 && confirmCloseTab()) closeSession(i)
            }
        }
        val tab = TabHeader().apply {
            // Paint an explicit, theme-aware background — unlike a Swing JTabbedPane tab component, this
            // strip is our own component and is always painted, so the title and close button stay visible.
            isOpaque = true
            background = UIUtil.getPanelBackground()
            // Explicit BorderLayout: the constraints below (WEST/EAST) are otherwise ignored and JBPanel's
            // default FlowLayout would center and wrap the close (✕) to a second line for long names.
            layout = BorderLayout(0, 0)
            // Padding doubles as the gap between adjacent tabs (BoxLayout adds none of its own).
            // Right inset is 0 so the flush ✕ (margin removed above) hugs the tab's right edge.
            border = JBUI.Borders.empty(2, JBUI.scale(4), 2, 0)
            // Name pinned to the left, close (✕) pinned to the right edge of the tab so it stays
            // right-aligned regardless of how long the agent name is.
            add(nameLabel, BorderLayout.WEST)
            add(closeBtn, BorderLayout.EAST)
            // Clicking the tab body (anywhere but the close button) selects this session.
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    if (closeBtn.bounds.contains(e.point)) return
                    selectByWidget(widget)
                }
            })
        }
        // tabBar is a BoxLayout(X_AXIS), which sizes each tab to its *content* — so without an explicit
        // width the close button hugs the name instead of sitting at the tab's right edge. Pin a minimum
        // tab width (growing for long names so they never clip) and cap the maximum so BoxLayout can't
        // stretch the tab to fill the strip. The name stays left and the ✕ is pushed to the right edge.
        val contentW = nameLabel.preferredSize.width + JBUI.scale(8) + closeBtn.preferredSize.width
        val tabW = contentW.coerceAtLeast(JBUI.scale(160))
        tab.minimumSize = Dimension(tabW, tab.minimumSize.height)
        tab.preferredSize = Dimension(tabW, tab.preferredSize.height)
        tab.maximumSize = Dimension(tabW, tab.maximumSize.height)
        return tab
    }

    /** Tear down the session at [index]: kill its PTY, close its widget, and remove its tab. */
    private fun closeSession(index: Int) {
        if (index !in sessions.indices) return
        val session = sessions[index]
        tabBar.remove(session.tabComp)
        contentArea.remove(session.widget)
        sessions.removeAt(index)
        runCatching { session.widget.close() }
        runCatching { session.process.destroyForcibly() }
        if (sessions.isEmpty()) {
            selectedIndex = -1
            cardLayout.show(contentArea, CARD_EMPTY)
        } else {
            // Keep a neighbour selected after a middle removal (indices shifted, so clamp).
            selectedIndex = selectedIndex.coerceIn(0, sessions.lastIndex)
            cardLayout.show(contentArea, sessions[selectedIndex].cardKey)
        }
        updateTabSelection()
        updateTabDropdownVisibility()
        tabBarScroll.revalidate()
        tabBarScroll.repaint()
    }

    /** Ask before tearing down a terminal, since closing kills the running PTY process. */
    private fun confirmCloseTab(): Boolean {
        return Messages.showYesNoDialog(
            project,
            message("panel.closeTabConfirm"),
            message("panel.closeTabConfirmTitle"),
            Messages.getQuestionIcon()
        ) == Messages.YES
    }

    /**
     * Toggle-style dropdown to the right of the settings gear: lists every open terminal and switches to
     * the chosen one. Saves the user from scrolling the overflowing tab strip when many agents are open.
     * A plain JPopupMenu is used (over JBPopupFactory) because the latter lives in app-client.jar, which
     * is not on this plugin's compile classpath.
     */
    private fun showTabDropdown() {
        // A single tab is the one already on screen, so there is nothing to switch to.
        if (sessions.size <= 1) return
        val menu = JPopupMenu()
        sessions.forEachIndexed { i, s ->
            // Same icon the tab strip shows for this agent, so the popup matches the tabs.
            val icon = if (s.row.command.isBlank()) null else AgentIcons.forAgent(s.row.id, s.row.iconPath)
            val item = JMenuItem(s.row.displayName, icon).apply {
                // The active tab is already on screen, so disable it rather than offer a no-op switch — but
                // keep its icon full-color via disabledIcon so a disabled item's icon isn't greyed out.
                isEnabled = i != selectedIndex
                disabledIcon = icon
                addActionListener { selectTab(i) }
            }
            menu.add(item)
        }
        menu.show(tabDropdownBtn, 0, tabDropdownBtn.height)
    }

    override fun dispose() {
        IdeEventQueue.getInstance().removeDispatcher(terminalKeyDispatcher)
        Toolkit.getDefaultToolkit().removePropertyChangeListener("awt.font.desktophints", scaleChangeListener)
        sessions.forEach { session ->
            runCatching { session.widget.close() }
            runCatching { session.process.destroyForcibly() }
        }
        sessions.clear()
    }
}
