package com.github.dtretyakov.pebble.run

import com.github.dtretyakov.pebble.project.PebbleProject
import com.github.dtretyakov.pebble.project.PebbleProjectInfo
import com.github.dtretyakov.pebble.project.PebbleSdkService
import com.github.dtretyakov.pebble.sdk.PebblePlatform
import com.github.dtretyakov.pebble.ui.PebbleConfigurable
import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.configurations.RuntimeConfigurationWarning
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project

/**
 * A Pebble run: build (a before-launch task, so it shows in the Build window and can be turned
 * off like any other), install with pebble-tool, then stream the app's logs until stopped.
 */
class PebbleRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    RunConfigurationBase<PebbleRunOptions>(project, factory, name) {

    public override fun getOptions(): PebbleRunOptions = super.getOptions() as PebbleRunOptions

    override fun getOptionsClass(): Class<out RunConfigurationOptions> = PebbleRunOptions::class.java

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState =
        PebbleRunState(environment, this)

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = PebbleRunSettingsEditor(project)

    /** The platform this run is for: its own pin, or the one beside the Run button. */
    fun platform(): String? {
        options.platform.takeIf { it.isNotBlank() }?.let { return it }
        val model = PebbleProject.getInstance(project)
        return model.primaryRoot()?.let { model.activePlatform(it) }
    }

    /**
     * Everything that would stop this run, said before the Run button is pressed, each with the
     * fix the platform shows beside it.
     */
    override fun checkConfiguration() {
        val model = PebbleProject.getInstance(project)
        val root = model.primaryRoot()
            ?: throw RuntimeConfigurationError("No Pebble project here: no package.json with a \"pebble\" section.")

        if (PebbleSdkService.getInstance().tool() == null) {
            throw RuntimeConfigurationError(
                "pebble-tool is not installed.",
                Runnable { ShowSettingsUtil.getInstance().showSettingsDialog(project, PebbleConfigurable::class.java) },
            )
        }

        val info = model.infoAt(root)
        if (info?.projectType == PebbleProjectInfo.ProjectType.PACKAGE) {
            throw RuntimeConfigurationError("This project is a package, a library for other projects: there is no app to run.")
        }

        when (options.target) {
            PebbleRunTarget.EMULATOR -> {
                val platform = platform()
                    ?: throw RuntimeConfigurationError("The project targets no platform the SDK has.")
                if (platform !in model.platforms(root)) {
                    throw RuntimeConfigurationError("The project does not target $platform, so its bundle has no binary for it.")
                }
                if (info?.projectType == PebbleProjectInfo.ProjectType.ALLOY && PebblePlatform.of(platform)?.alloy == false) {
                    throw RuntimeConfigurationWarning("Alloy runs on emery and gabbro only, not on $platform.")
                }
            }
            PebbleRunTarget.PHONE -> if (options.phoneAddress.isBlank()) {
                throw RuntimeConfigurationError(
                    "Enter the phone's address, as the Pebble app shows it under Developer Connection.",
                )
            }
            PebbleRunTarget.CLOUDPEBBLE, PebbleRunTarget.ADB -> Unit
        }
    }
}
