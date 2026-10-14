package com.github.dtretyakov.pebble.run

import com.github.dtretyakov.pebble.testing.IdeTestCase
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor

class PebbleDebugRunnerTest : IdeTestCase() {

    private fun configuration() = ConfigurationTypeUtil.findConfigurationType(PebbleRunConfigurationType::class.java)
        .configurationFactories.first().createTemplateConfiguration(project) as PebbleRunConfiguration

    fun `test Debug builds with --debug, Run only when asked`() {
        val configuration = configuration()
        assertTrue(PebbleDebugRunner.debugBuild(configuration, DefaultDebugExecutor.EXECUTOR_ID))
        assertFalse(PebbleDebugRunner.debugBuild(configuration, DefaultRunExecutor.EXECUTOR_ID))
        configuration.options.debugBuild = true
        assertTrue(PebbleDebugRunner.debugBuild(configuration, DefaultRunExecutor.EXECUTOR_ID))
    }

    fun `test Debug is not claimed where there is no Alloy project`() {
        assertFalse(PebbleDebugRunner().canRun(DefaultDebugExecutor.EXECUTOR_ID, configuration()))
        assertFalse(PebbleDebugRunner().canRun(DefaultRunExecutor.EXECUTOR_ID, configuration()))
    }
}
