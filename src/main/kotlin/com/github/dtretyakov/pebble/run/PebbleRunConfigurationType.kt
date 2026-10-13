package com.github.dtretyakov.pebble.run

import com.github.dtretyakov.pebble.ui.PebbleIcons
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NotNullLazyValue

class PebbleRunConfigurationType : ConfigurationTypeBase(
    ID,
    "Pebble",
    "Builds a Pebble app and installs it in the emulator or on a watch, then shows its logs.",
    NotNullLazyValue.createValue { PebbleIcons.PEBBLE },
) {
    init {
        addFactory(Factory(this))
    }

    class Factory(type: PebbleRunConfigurationType) : ConfigurationFactory(type) {
        override fun getId(): String = "Pebble App"

        override fun createTemplateConfiguration(project: Project): RunConfiguration =
            PebbleRunConfiguration(project, this, "Pebble")

        override fun getOptionsClass(): Class<out BaseState> = PebbleRunOptions::class.java
    }

    companion object {
        const val ID = "PebbleRunConfiguration"
    }
}
