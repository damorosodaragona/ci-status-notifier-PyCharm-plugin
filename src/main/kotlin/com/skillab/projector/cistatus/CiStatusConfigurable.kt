package com.skillab.projector.cistatus

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel

class CiStatusConfigurable(private val project: Project) : Configurable {
    private val settings = CiStatusSettings.getInstance(project)

    private val enabled = JBCheckBox("Poll GitHub commit statuses")
    private val repository = JBTextField()
    private val token = JBPasswordField()
    private val pollInterval = JBTextField()
    private val notifyPending = JBCheckBox("Notify pending statuses")
    private val notifySuccess = JBCheckBox("Notify successful statuses")
    private val notifyFailure = JBCheckBox("Notify failed/error statuses")
    private var panel: JPanel? = null

    override fun getDisplayName(): String = "CI Status Notifier"

    override fun createComponent(): JComponent {
        panel = FormBuilder.createFormBuilder()
            .addComponent(enabled)
            .addLabeledComponent("GitHub repository", repository)
            .addLabeledComponent("GitHub token", token)
            .addComponent(JBLabel("Repository format: owner/name. Token is stored in the JetBrains Password Safe."))
            .addLabeledComponent("Poll interval seconds", pollInterval)
            .addComponent(notifyPending)
            .addComponent(notifySuccess)
            .addComponent(notifyFailure)
            .addComponentFillVertically(JPanel(), 0)
            .panel
        reset()
        return panel!!
    }

    override fun isModified(): Boolean {
        return enabled.isSelected != settings.enabled ||
            repository.text.trim() != settings.repository ||
            String(token.password) != settings.getToken() ||
            pollInterval.text.trim() != settings.pollIntervalSeconds.toString() ||
            notifyPending.isSelected != settings.notifyPending ||
            notifySuccess.isSelected != settings.notifySuccess ||
            notifyFailure.isSelected != settings.notifyFailure
    }

    override fun apply() {
        settings.enabled = enabled.isSelected
        settings.repository = repository.text
        settings.setToken(String(token.password))
        settings.pollIntervalSeconds = pollInterval.text.toIntOrNull() ?: 60
        settings.notifyPending = notifyPending.isSelected
        settings.notifySuccess = notifySuccess.isSelected
        settings.notifyFailure = notifyFailure.isSelected
    }

    override fun reset() {
        enabled.isSelected = settings.enabled
        repository.text = settings.repository
        token.text = settings.getToken()
        pollInterval.text = settings.pollIntervalSeconds.toString()
        notifyPending.isSelected = settings.notifyPending
        notifySuccess.isSelected = settings.notifySuccess
        notifyFailure.isSelected = settings.notifyFailure
    }
}
