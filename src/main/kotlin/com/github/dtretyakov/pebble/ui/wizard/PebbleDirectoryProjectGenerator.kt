package com.github.dtretyakov.pebble.ui.wizard

import com.github.dtretyakov.pebble.project.NewPebbleProject
import com.github.dtretyakov.pebble.project.PebbleSdkService
import com.github.dtretyakov.pebble.ui.PebbleIcons
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.DirectoryProjectGeneratorBase
import com.intellij.platform.GeneratorPeerImpl
import com.intellij.platform.ProjectGeneratorPeer
import com.intellij.ui.dsl.builder.panel
import javax.swing.Icon

/**
 * "Pebble" in the New Project dialog of the IDEs without IDEA's wizard — CLion and WebStorm —
 * offering the same choices and making the project the same way.
 */
class PebbleDirectoryProjectGenerator : DirectoryProjectGeneratorBase<NewPebbleProject.Options>() {

    override fun getName(): String = "Pebble"

    override fun getDescription(): String =
        "A watchapp or watchface for Pebble, in C or Alloy, from the templates in the Pebble SDK."

    override fun getLogo(): Icon = PebbleIcons.PEBBLE

    override fun createPeer(): ProjectGeneratorPeer<NewPebbleProject.Options> {
        val options = NewPebbleProject.Options()
        val form: DialogPanel = panel { NewProjectOptionsUi.build(this, options) }
        return object : GeneratorPeerImpl<NewPebbleProject.Options>(options, form) {
            override fun getSettings(): NewPebbleProject.Options = options.also { form.apply() }

            override fun validate(): ValidationInfo? =
                if (PebbleSdkService.getInstance().tool() == null) ValidationInfo("Install pebble-tool before creating a project.") else null
        }
    }

    override fun generateProject(project: Project, baseDir: VirtualFile, settings: NewPebbleProject.Options, module: Module) {
        val tool = PebbleSdkService.getInstance().tool() ?: return
        NewPebbleProject.generate(tool, baseDir.toNioPath(), settings)
        baseDir.refresh(false, true)
    }
}
