package com.cnsharp.yolo.launcher

import com.cnsharp.yolo.YoloBundle
import com.cnsharp.yolo.settings.AgentExtenderSettings
import com.cnsharp.yolo.terminal.AgentIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import javax.swing.Icon

/**
 * Global "Skip permissions" toggle state (stored in Settings).
 *
 * Shown in the YOLO panel header (added by YoloToolWindowFactory) as a toolbar ToggleAction,
 * next to the settings gear.
 *
 * The icon is a lowercase y: gray when off, red when on — visually consistent with a checkbox/toggle,
 * but the custom icon lets users immediately recognize this as the "skip permissions" switch.
 */
/* The toolbar adds this as a freshly-instantiated action (not the plugin.xml-registered singleton), so the
 * platform does NOT inject the bundle text/description into its template presentation. Set the description here
 * (icon-only button, so only the tooltip matters) so the hover tooltip still appears and follows the IDE language. */
class SkipPermissionsAction : ToggleAction() {

    init {
        templatePresentation.text =
            YoloBundle.message("action.com.cnsharp.yolo.SkipPermissionsAction.text")
        templatePresentation.description =
            YoloBundle.message("action.com.cnsharp.yolo.SkipPermissionsAction.description")
    }
    override fun isSelected(e: AnActionEvent): Boolean =
        AgentExtenderSettings.getInstance().state.skipEnabled

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        AgentExtenderSettings.getInstance().state.skipEnabled = state
    }

    /** State only reads the in-memory settings and does no PATH probing, so it can be safely evaluated on the EDT. */
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    /** Dynamically switch the icon: off = gray y, on = red y. */
    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.icon = if (isSelected(e)) AgentIcons.SKIP_Y_ON else AgentIcons.SKIP_Y_OFF
    }
}
