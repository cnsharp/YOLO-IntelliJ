package com.cnsharp.yolo.launcher

import com.cnsharp.yolo.YoloBundle
import com.cnsharp.yolo.settings.AgentExtenderSettings
import com.cnsharp.yolo.terminal.AgentIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction

/**
 * Global "Resume session" toggle state (stored in Settings).
 *
 * Shown in the YOLO panel header (added by YoloToolWindowFactory) as a toolbar ToggleAction, next to the
 * "Skip permissions" toggle and the settings gear.
 *
 * When on, the selected agent is launched with its `resumeFlag` (from agents.json) appended to the command,
 * so the agent resumes the most recent (or a chosen) session. Agents without a resumeFlag are launched normally.
 *
 * The icon is a replay mark: gray when off, green when on — visually consistent with the Skip-permissions toggle.
 */
/* The toolbar adds this as a freshly-instantiated action (not the plugin.xml-registered singleton), so the
 * platform does NOT inject the bundle text/description into its template presentation. Set the description here
 * (icon-only button) so the hover tooltip appears and follows the IDE language. */
class ResumeAction : ToggleAction() {

    init {
        templatePresentation.text =
            YoloBundle.message("action.com.cnsharp.yolo.ResumeAction.text")
        templatePresentation.description =
            YoloBundle.message("action.com.cnsharp.yolo.ResumeAction.description")
    }
    override fun isSelected(e: AnActionEvent): Boolean =
        AgentExtenderSettings.getInstance().state.resumeEnabled

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        AgentExtenderSettings.getInstance().state.resumeEnabled = state
    }

    /** State only reads the in-memory settings, so it can be safely evaluated on the EDT. */
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    /** Dynamically switch the icon: off = gray replay, on = green replay. */
    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.icon = if (isSelected(e)) AgentIcons.RESUME_ON else AgentIcons.RESUME_OFF
    }
}
