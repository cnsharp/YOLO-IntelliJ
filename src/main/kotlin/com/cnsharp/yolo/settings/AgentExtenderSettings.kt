package com.cnsharp.yolo.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.messages.Topic
import com.intellij.util.xmlb.XmlSerializerUtil

/** "Skip permissions" rule for a single tool: only stores this agent's skip flag value;
 *  whether it is actually injected is decided by the toolbar's global "Skip permissions" checkbox (skipEnabled). */
data class PermissionRule(
    var agentId: String = "",
    var flag: String = "--dangerously-skip-permissions"
)

/** "Resume session" rule for a single tool: stores the agent's resume flag value;
 *  whether it is actually injected is decided by the toolbar's global "Resume session" checkbox (resumeEnabled).
 *  Custom tools have no agents.json entry, so this is the only way to give them a resume flag. */
data class ResumeRule(
    var agentId: String = "",
    var flag: String = ""
)

/** A custom tool that appears in the terminal "AI Agents" dropdown (user-added, distinct from IDEA's built-in agents). */
data class CustomTool(
    var id: String = "",
    var displayName: String = "",
    var command: String = "",
    var baseArgs: String = "",
    /** Icon shown in the dropdown: absolute path to a local file (.svg preferred, .png also supported). If blank, an icon bundled with the package is looked up by id, falling back to a default. */
    var iconPath: String = ""
)


@State(name = "AgentExtenderSettings", storages = [Storage("agentExtender.xml")])
@Service(Service.Level.APP)
class AgentExtenderSettings : PersistentStateComponent<AgentExtenderSettings.State> {

    private var currentState: State = State()

    private var syncScheduled = false

    init {
        // On every IDE startup (first access to the service, i.e. one session), trigger one background sync:
        // add the currently "installed" built-in / promoted agents into the config so the terminal dropdown
        // and settings panel reflect the latest install state.
        // The old logic only probed once when the config was empty, so agents installed after first run (e.g. codebuddy)
        // could never get in and you had to clear the config and rerun — which is exactly why "reload on every startup" is needed.
        // Must run on a background thread (the probe spawns processes); never on the EDT.
        ensureSyncScheduled()
    }

    /** Schedule a background sync (once only; a flag guarantees idempotency, so repeated calls are harmless). */
    fun ensureSyncScheduled() {
        if (syncScheduled) return
        syncScheduled = true
        // Show the previous hot-update catalog instantly from disk before any network work.
        AgentCatalogUpdater.loadCached()
        val app = ApplicationManager.getApplication()
        app.executeOnPooledThread {
            // Finalize the remote catalog FIRST, then decide which commands are installed against that
            // final registry. Probing installs first made a freshly added agent (e.g. qoder) get judged
            // against a stale/partial catalog, so it only appeared after a restart warmed the cache.
            AgentCatalogUpdater.refreshBlocking()
            syncInstalledAgents()
        }
    }

    /**
     * Reconcile the machine's installed agents into the config; called once per startup, after the
     * remote catalog has been finalized in [AgentRegistry].
     *  - Full replace of the persisted installedCommands cache: probe (command -v / --version) every
     *    catalog command plus user-added custom tools, and drop anything no longer resolvable (e.g.
     *    qoder after ~/.qoder was removed). This is idempotent across restarts.
     *  - For each detected installed catalog agent, ensure a permission rule (skip flag) exists.
     *    Built-ins are NOT written to customTools — they come from the catalog and show in the panel
     *    via installedCommands. Any legacy customTools entry for a catalog agent is pruned below.
     *  - Finally fireChanged() on the EDT so every install-state view refreshes immediately.
     *  Note: spawns processes, so the caller must ensure this runs on a background thread.
     */
    fun syncInstalledAgents() {
        // Reconcile the persisted install cache against the FINAL catalog (the remote list was
        // merged in just before this). Include user-added custom tools so their commands are not
        // dropped. This is a full replace, not markInstalled: an agent uninstalled since last run
        // (e.g. qoder after ~/.qoder was removed) must stop being reported as installed.
        val commands = (AgentRegistry.agents.asSequence().map { it.command } +
            currentState.customTools.asSequence().map { it.command }).distinct().toList()
        val installed = InstalledAgents.syncInstalled(commands)

        for (def in AgentRegistry.agents) {
            if (def.command.lowercase() !in installed) continue
            if (currentState.permissionRules.none { it.agentId == def.command }) {
                currentState.permissionRules.add(PermissionRule(def.command, def.skipFlag))
            }
        }

        // Prune legacy customTools entries that merely mirror a catalog agent (e.g. qoder, which older
        // versions auto-added here). They are redundant — built-ins come from the catalog — and made a
        // removed built-in look like a user custom tool. Genuine user additions (not in the catalog) stay.
        val catalogIds = AgentRegistry.agents.map { it.id.lowercase() }.toSet()
        val catalogCommands = AgentRegistry.agents.map { it.command.lowercase() }.toSet()
        currentState.customTools.removeAll { tool ->
            tool.id.lowercase() in catalogIds || tool.command.lowercase() in catalogCommands
        }
        // Detection is complete — refresh every list that renders install state (terminal dropdown,
        // Manage Agents page, Settings table) on the EDT. This is what makes an uninstalled agent
        // (e.g. qoder after ~/.qoder was removed) disappear without a restart or manual reopen.
        ApplicationManager.getApplication().invokeLater { fireChanged() }
    }

    override fun getState(): State = currentState

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, currentState)
    }

    class State {
        // Toolbar global "Skip permissions" checkbox state; off by default, only injected when the user explicitly checks it.
        var skipEnabled: Boolean = false
        // Toolbar global "Resume session" checkbox state; off by default, only injects the agent's resumeFlag when checked.
        var resumeEnabled: Boolean = false
        /**
         * When true, the "Skip permissions" launch warning is suppressed. Set when the user ticks
         * "Don't show this warning again" in the warning dialog (or the matching Settings checkbox), so the
         * warning never pops up again.
         */
        var skipWarningDismissed: Boolean = false
        // Last agent launched in the YOLO panel (by agent id); restored as the dropdown's default selection on next open.
        var lastAgentId: String = ""
        // Each agent's skip flag value (from Settings); whether it is injected is controlled by skipEnabled.
        var permissionRules: MutableList<PermissionRule> = mutableListOf()
        // Each agent's resume flag value (from Settings); whether it is injected is controlled by resumeEnabled.
        // Falls back to AgentRegistry's agents.json value when empty. Custom tools have no agents.json entry, so they rely on this.
        var resumeRules: MutableList<ResumeRule> = mutableListOf()
        var customTools: MutableList<CustomTool> = mutableListOf()
        /**
         * Per-agent extra launch arguments (base args) keyed by lower-cased agent id. Only needed for
         * promoted agents (e.g. codebuddy), because those are NOT written into [customTools] — without this
         * map, an edit to a promoted agent's "Base args" column in Settings would be silently dropped on save.
         * User-added custom tools store their base args on the [CustomTool] itself.
         */
        var agentBaseArgs: MutableMap<String, String> = mutableMapOf()
        /**
         * Cache of commands (lower-cased) detected as installed on the machine. Persisted so the dropdown
         * loads instantly from this cache on the next open; a background re-scan refreshes it and only
         * touches the dropdown when the detected set actually differs.
         */
        var installedCommands: MutableList<String> = mutableListOf()
        /**
         * RGB of the YOLO panel terminal's hyperlink color. Defaults to a muted steel blue so links read
         * as links without dominating the output; user-configurable via Settings | Tools | YOLO.
         */
        var linkColorRgb: Int = DEFAULT_LINK_COLOR_RGB
    }

    companion object {
        /** Default terminal hyperlink color: a darker, muted steel blue (vs. JediTerm's pure BLUE 0x0000FF). */
        const val DEFAULT_LINK_COLOR_RGB: Int = 0x1E64B4

        /** Fired on Settings | Tools | YOLO → Apply, so the live terminal panel can refresh its dropdown (e.g. base args). */
        val CHANGED: Topic<AgentExtenderSettingsListener> =
            Topic.create("AgentExtenderSettings.Changed", AgentExtenderSettingsListener::class.java)

        /** Lightweight signal: re-read the installed-agents cache and rebuild the terminal dropdown WITHOUT
         *  re-scanning PATH. Fired after an install that already verified its command, so only the one new
         *  agent appears — no full probe of every other agent. */
        val DROPDOWN_REFRESH: Topic<AgentExtenderSettingsListener> =
            Topic.create("AgentExtenderSettings.DropdownRefresh", AgentExtenderSettingsListener::class.java)

        fun getInstance(): AgentExtenderSettings =
            com.intellij.openapi.components.service<AgentExtenderSettings>()
                .also { it.ensureSyncScheduled() }
    }

    /** Publish a change so subscribers (the YOLO terminal panel) can refresh their dropdown from current state. */
    fun fireChanged() {
        ApplicationManager.getApplication().messageBus.syncPublisher(CHANGED).changed()
    }

    /** Publish a lightweight dropdown refresh (re-read cache only, no PATH re-scan). */
    fun fireDropdownRefresh() {
        ApplicationManager.getApplication().messageBus.syncPublisher(DROPDOWN_REFRESH).changed()
    }
}

/** Notified when the user applies changes in Settings | Tools | YOLO. */
interface AgentExtenderSettingsListener {
    fun changed()
}
