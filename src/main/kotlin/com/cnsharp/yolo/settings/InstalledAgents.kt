package com.cnsharp.yolo.settings

import com.intellij.openapi.application.ApplicationManager

/**
 * Shared "installed agents" cache, used by both the terminal dropdown panel and the Settings page so they
 * render install status through one mechanism.
 *
 * The cache ([AgentExtenderSettings.State.installedCommands]) is a machine-wide, lower-cased set of commands
 * currently resolvable on PATH. It is persisted so the UI can render instantly on open without spawning a
 * probe; [rescan] re-detects the given commands on a background thread, updates the cache, and notifies the
 * caller only when the detected set actually changed.
 *
 * Staleness is the caller's responsibility: [rescan] may be started several times concurrently (e.g. the
 * dropdown rebuilding while a previous scan is still running), so callers guard their [onChanged] callback
 * with their own generation token — the dropdown panel uses [YoloPanel.refreshGeneration] for exactly this.
 */
object InstalledAgents {

    /** Lower-cased commands currently cached as installed. Cheap read; safe on any thread. */
    fun installed(): Set<String> =
        AgentExtenderSettings.getInstance().state.installedCommands.toSet()

    /**
     * Re-detect [commands] for presence on PATH on a background thread, persist the result into the shared
     * cache, and invoke [onChanged] on the EDT **only when** the detected set differs from the cache — with
     * the up-to-date installed set. Callers must guard [onChanged] against stale concurrent scans.
     */
    fun rescan(commands: List<String>, onChanged: (Set<String>) -> Unit) {
        val app = ApplicationManager.getApplication()
        app.executeOnPooledThread {
            val detected = commands.asSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinctBy { it.lowercase() }
                .filter { AgentDetector.canExecute(it) }
                .map { it.lowercase() }
                .toSet()
            app.invokeLater {
                val state = AgentExtenderSettings.getInstance().state
                if (detected == state.installedCommands.toSet()) return@invokeLater
                state.installedCommands.clear()
                state.installedCommands.addAll(detected.sorted())
                onChanged(state.installedCommands.toSet())
            }
        }
    }

    /**
     * Incrementally mark a single command as installed — call this right after an install finished and we
     * already verified the command runs, so we add just this one command to the cache **without** re-scanning
     * PATH for every other agent. Persists the change. Returns true iff the cache actually changed.
     */
    fun markInstalled(command: String): Boolean {
        val key = command.trim().lowercase()
        if (key.isEmpty()) return false
        val state = AgentExtenderSettings.getInstance().state
        if (key in state.installedCommands) return false
        state.installedCommands.add(key)
        return true
    }
}
