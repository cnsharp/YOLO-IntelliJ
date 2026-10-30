package com.cnsharp.yolo.settings

import com.cnsharp.yolo.Yolo
import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger

/**
 * Post-startup hook: when the user enables "Check for agent updates on startup"
 * ([AgentExtenderSettings.State.autoCheckUpdatesOnStartup]), kick off the same
 * [AgentUpdateChecker.checkAll] scan the Manage Agents tab runs. This is the "unified" behavior the
 * user asked for — one checker, one cache: the background scan populates the in-memory results and the
 * persisted version cache, so when Manage Agents is later opened it shows the already-probed versions
 * (seeded from cache) instead of needing its own first scan.
 *
 * Uses [AppLifecycleListener.appStarted] (not the deprecated [com.intellij.openapi.startup.StartupActivity])
 * so the settings service is already loaded and the persisted toggle value is readable. The scan itself runs
 * on a pooled thread because it spawns processes / does HTTP lookups.
 */
class AutoUpdateStartupActivity : AppLifecycleListener {

    private val LOG = Logger.getInstance(AutoUpdateStartupActivity::class.java)

    override fun appStarted() {
        if (!AgentExtenderSettings.getInstance().state.autoCheckUpdatesOnStartup) return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                AgentUpdateChecker.checkAll()
            } catch (e: Exception) {
                LOG.warn("${Yolo.NAME}: startup agent update check failed", e)
            }
        }
    }
}
