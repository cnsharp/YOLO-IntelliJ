package com.cnsharp.yolo.settings

import com.cnsharp.yolo.YoloBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil

/**
 * The gear button in the YOLO panel header, opening this plugin's settings page with one click.
 *
 * Added to the panel's toolbar by YoloToolWindowFactory.
 * Locate the settings page by the Configurable class rather than its display name: the display name changes
 * with the IDE language, the class does not.
 */
/* The toolbar adds this as a freshly-instantiated action (not the plugin.xml-registered singleton), so the
 * platform does NOT inject the bundle text/description into its template presentation. Set the description here
 * (icon-only button) so the hover tooltip appears and follows the IDE language. */
class OpenSettingsAction : AnAction(AllIcons.General.Settings) {

    init {
        templatePresentation.text =
            YoloBundle.message("action.com.cnsharp.yolo.OpenSettingsAction.text")
        templatePresentation.description =
            YoloBundle.message("action.com.cnsharp.yolo.OpenSettingsAction.description")
    }

    override fun actionPerformed(e: AnActionEvent) {
        ShowSettingsUtil.getInstance().showSettingsDialog(e.project, AgentExtenderConfigurable::class.java)
    }

    /** No update logic; the icon and text are static, so evaluating on a background thread is fine. */
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
