package com.github.dtretyakov.pebble.run

import com.github.dtretyakov.pebble.project.PebbleProject
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import java.nio.file.Path
import kotlin.io.path.name

/**
 * Offers to run the app from anywhere inside a Pebble project — any file, or the folder — since
 * a project has exactly one obvious thing to run.
 */
class PebbleRunConfigurationProducer : LazyRunConfigurationProducer<PebbleRunConfiguration>() {

    override fun getConfigurationFactory(): ConfigurationFactory =
        ConfigurationTypeUtil.findConfigurationType(PebbleRunConfigurationType::class.java).configurationFactories.first()

    override fun setupConfigurationFromContext(
        configuration: PebbleRunConfiguration,
        context: ConfigurationContext,
        source: Ref<PsiElement>,
    ): Boolean {
        val root = root(context) ?: return false
        configuration.name = root.name
        return true
    }

    override fun isConfigurationFromContext(configuration: PebbleRunConfiguration, context: ConfigurationContext): Boolean =
        root(context) != null

    private fun root(context: ConfigurationContext): Path? {
        val file = context.location?.virtualFile ?: return null
        return context.project?.let { PebbleProject.getInstance(it).rootFor(file) }
    }
}
