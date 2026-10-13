package com.github.dtretyakov.pebble.run

import com.github.dtretyakov.pebble.project.PebbleProject
import com.github.dtretyakov.pebble.project.PebbleProjectInfo
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import kotlin.io.path.name

/**
 * The one run configuration a Pebble app always has, made when the project opens.
 *
 * Without it the Run button is dead until the user finds the Run/Debug dialog, in a project where
 * there is exactly one thing to run. Only when there is none already, and selected only when
 * nothing is: a project opened before keeps whatever the user arranged. A package gets none — it
 * has no app.
 */
class PebbleRunConfigurations : ProjectActivity {

    override suspend fun execute(project: Project) {
        val root = readAction {
            val model = PebbleProject.getInstance(project)
            model.primaryRoot()?.takeIf { model.infoAt(it)?.projectType != PebbleProjectInfo.ProjectType.PACKAGE }
        } ?: return

        edtWriteAction {
            if (project.isDisposed) return@edtWriteAction
            val manager = RunManager.getInstance(project)
            val type = ConfigurationTypeUtil.findConfigurationType(PebbleRunConfigurationType::class.java)
            if (manager.getConfigurationsList(type).isNotEmpty()) return@edtWriteAction

            val settings = manager.createConfiguration(root.name, type.configurationFactories.first())
            manager.addConfiguration(settings)
            if (manager.selectedConfiguration == null) manager.selectedConfiguration = settings
        }
    }
}
