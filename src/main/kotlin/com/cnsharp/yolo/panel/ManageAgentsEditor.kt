package com.cnsharp.yolo.panel

import com.cnsharp.yolo.YoloBundle.message
import com.intellij.openapi.Disposable
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import java.beans.PropertyChangeListener
import javax.swing.Icon
import javax.swing.JComponent

/**
 * A synthetic, non-persisted [VirtualFile] backing the "Manage Agents" editor tab. The tab is opened in
 * the main editor area (not the YOLO tool window) via [com.intellij.openapi.fileEditor.FileEditorManager],
 * mirroring how VSCode shows its Agent Manager as a full editor-style page. A single instance is kept per
 * project so re-selecting the dropdown entry re-selects the existing tab instead of opening a duplicate.
 */
private class ManageAgentsVirtualFile : LightVirtualFile(message("manage.heading")) {
    init {
        fileType = ManageAgentsFileType
        isWritable = false
    }
}

/** Custom [FileType] so only [ManageAgentsEditorProvider] handles the synthetic file (the platform's text
 *  editor provider ignores non-plain-text types, so there is no competing editor). */
private object ManageAgentsFileType : FileType {
    override fun getName(): String = "YOLO_MANAGE_AGENTS"
    override fun getDescription(): String = message("manage.heading")
    override fun getDefaultExtension(): String = ""
    override fun getIcon(): Icon? = null
    override fun isBinary(): Boolean = false
}

/**
 * Opens the "Manage Agents" page as a tab in the main editor area. Registered via the `fileEditorProvider`
 * extension point; [accept] matches only our synthetic [ManageAgentsVirtualFile].
 */
class ManageAgentsEditorProvider : FileEditorProvider, DumbAware {

    override fun getEditorTypeId(): String = "yolo.manage.agents"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR

    override fun accept(project: Project, file: VirtualFile): Boolean = file is ManageAgentsVirtualFile

    override fun createEditor(project: Project, file: VirtualFile): FileEditor = ManageAgentsEditor(project, file)

    companion object {
        private val FILE_KEY = Key<VirtualFile>("yolo.manage.agents.file")

        /** The (per-project) synthetic file backing the Manage Agents editor tab. */
        fun virtualFile(project: Project): VirtualFile =
            project.getUserData(FILE_KEY) ?: ManageAgentsVirtualFile().also { project.putUserData(FILE_KEY, it) }
    }
}

/** A [FileEditor] whose content is the [ManageAgentsPanel] card grid. Mirrors the VSCode Agent Manager. */
private class ManageAgentsEditor(private val project: Project, private val file: VirtualFile) : UserDataHolderBase(), FileEditor {

    private val panel = ManageAgentsPanel(project)

    override fun getFile(): VirtualFile = file

    override fun getComponent(): JComponent = panel

    override fun getPreferredFocusedComponent(): JComponent = panel

    override fun getName(): String = message("manage.heading")

    override fun setState(state: FileEditorState) {}

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = true

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}

    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}

    override fun dispose() {
        Disposer.dispose(panel as Disposable)
    }
}
