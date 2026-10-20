package com.github.dtretyakov.pebble.ui.wizard

import com.github.dtretyakov.pebble.project.NewPebbleProject
import com.github.dtretyakov.pebble.project.PebbleSdkService
import com.github.dtretyakov.pebble.ui.PebbleIcons
import com.intellij.ide.wizard.AbstractNewProjectWizardStep
import com.intellij.ide.wizard.GeneratorNewProjectWizard
import com.intellij.ide.wizard.NewProjectWizardBaseData.Companion.baseData
import com.intellij.ide.wizard.NewProjectWizardBaseStep
import com.intellij.ide.wizard.NewProjectWizardChainStep.Companion.nextStep
import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.ide.wizard.RootNewProjectWizardStep
import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.Panel
import java.nio.file.Path
import javax.swing.Icon

/** "Pebble" in IDEA's New Project dialog. */
class PebbleNewProjectWizard : GeneratorNewProjectWizard {

    override val id: String = "Pebble"

    override val name: String = "Pebble"

    override val icon: Icon = PebbleIcons.PEBBLE

    override val description: String =
        "A watchapp or watchface for Pebble, in C or Alloy, from the templates in the Pebble SDK."

    override fun createStep(context: WizardContext): NewProjectWizardStep =
        RootNewProjectWizardStep(context)
            .nextStep(::NewProjectWizardBaseStep)
            .nextStep(::Step)

    private class Step(parent: NewProjectWizardStep) : AbstractNewProjectWizardStep(parent) {

        private val options = NewPebbleProject.Options()

        override fun setupUI(builder: Panel) = NewProjectOptionsUi.build(builder, options)

        override fun setupProject(project: Project) {
            val tool = PebbleSdkService.getInstance().tool() ?: return
            val base = baseData ?: return
            val root = Path.of(base.path, base.name)
            NewPebbleProject.generateWithProgress(project, tool, root, options)
        }
    }
}
