package com.cnsharp.yolo.panel

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.IconLoader

/**
 * The robot button in the YOLO panel header, placed to the LEFT of the settings gear: opens the
 * "Manage Agents" page (Agent Manager) as an editor tab, mirroring the VSCode Agent Manager entry.
 * Added to the panel's toolbar by YoloToolWindowFactory.
 *
 * The text/description are not hardcoded here: the <action> in plugin.xml declares the <resource-bundle>,
 * so the platform pulls action.<id>.text / .description from YoloBundle and follows the IDE language switch.
 */
class ManageAgentsAction : AnAction() {

    init {
        // Set the robot icon here (not via the super constructor) so we don't reference `this` (javaClass)
        // before the instance is initialized. The bundled robot.svg works on all IDE builds.
        templatePresentation.icon = IconLoader.getIcon("/icons/robot.svg", javaClass)
        // The toolbar adds this as a freshly-instantiated action (not the plugin.xml-registered singleton),
        // so the platform does NOT inject the bundle text/description into its template presentation. Set both
        // text and description here (icon-only button) so the hover tooltip appears and follows the IDE language.
        templatePresentation.text =
            com.cnsharp.yolo.YoloBundle.message("action.com.cnsharp.yolo.ManageAgentsAction.text")
        templatePresentation.description =
            com.cnsharp.yolo.YoloBundle.message("action.com.cnsharp.yolo.ManageAgentsAction.description")
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = ManageAgentsEditorProvider.virtualFile(project)
        FileEditorManager.getInstance(project).openFile(file, true)
    }

    /** No update logic; the icon and text are static, so evaluating on a background thread is fine. */
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
